package io.github.rafalpawlisz.shelfie.data.sync

import io.github.rafalpawlisz.shelfie.data.local.OneOffSuggestionEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductBarcodeEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductListOrderEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * Two-way sync between Room and households/{hid} subcollections.
 *
 * Per household session (a new session starts whenever the household
 * changes; none runs while signed out / without one):
 *
 *  1. PULL FIRST. Await the initial snapshot of every collection. If the
 *     remote household holds any data, reconcile Room to it in FK-parent
 *     order — LWW upserts plus deletion of rows absent remotely; this is
 *     what replaces local content after joining an existing household. An
 *     empty remote household skips the reconcile: local data becomes its
 *     seed via the push below.
 *  2. Keep applying every subsequent snapshot (LWW upserts + explicit
 *     REMOVED deletions; absence is never interpreted as deletion past the
 *     initial reconcile).
 *  3. PUSH. Mirror the full-content Room flows by diffing against what was
 *     last pushed — covers every local mutation path by construction; the
 *     session's first diff re-pushes current state (idempotent under LWW
 *     on other devices). Local deletions arrive via [onDeleted] from the
 *     repositories and go straight to the writer.
 *
 * Echoes terminate but are not free. A pushed document comes back with an
 * equal updatedAt, so the LWW upsert drops it — that side is silent. The other
 * side costs one redundant write per pulled change, because the push diff
 * compares against what THIS device pushed and a row applied from a pull looks
 * new. The session-start case of that was not merely a cost: an empty diff
 * cache re-pushed every local row, which resurrected rows another device had
 * deleted meanwhile. The cache is now seeded from the first server snapshot
 * (see [mirror]); the per-change echo is still outstanding.
 */
class DiffSyncEngine(
    private val householdIds: Flow<String?>,
    private val products: Flow<List<ProductEntity>>,
    private val lists: Flow<List<ShoppingListEntity>>,
    private val items: Flow<List<ShoppingListItemEntity>>,
    private val listOrders: Flow<List<ProductListOrderEntity>>,
    private val barcodes: Flow<List<ProductBarcodeEntity>>,
    private val oneOffSuggestions: Flow<List<OneOffSuggestionEntity>>,
    private val writer: SyncWriter,
    private val remote: RemoteSource,
    private val applier: SyncApplier,
    private val syncState: SyncStateStore,
    // Must be the same clock the repositories stamp rows with: lastSyncedAt is
    // compared against those updatedAt values, and mixing a corrected clock
    // with a raw one would make every row look newer than the mark, disabling
    // the reconcile's deletion arm.
    private val clock: SyncClock = SyncClock { System.currentTimeMillis() },
    private val scope: CoroutineScope,
    // Called once per session so an abandoned household is recognisable
    // later; failures must never take the session down with them.
    private val onSessionStart: suspend (householdId: String) -> Unit = {},
) : SyncEngine {

    // Current household for deletion hooks; null → hooks are no-ops.
    private val activeHousehold = MutableStateFlow<String?>(null)

    // Deletions reported before any household session exists (see onDeleted).
    private val pendingDeletesLock = Any()
    private val pendingDeletes = mutableListOf<Pair<SyncCollection, String>>()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)

    /** Live sync state for the settings screen. */
    val status: StateFlow<SyncStatus> = _status

    fun start() {
        scope.launch {
            // distinctUntilChanged is load-bearing: the household document
            // re-emits on membership/metadata changes, and collectLatest would
            // otherwise cancel and restart the whole session (fresh initial
            // snapshots + reconcile) on every such blip.
            householdIds.distinctUntilChanged().collectLatest { hid ->
                activeHousehold.value = hid
                if (hid == null) {
                    _status.value = SyncStatus.Off
                } else {
                    supervisorScope { runSession(hid) }
                }
            }
        }
    }

    override fun onDeleted(collection: SyncCollection, docIds: List<String>) {
        val hid = activeHousehold.value
        if (hid == null) {
            // Auth restore plus the household lookup take a moment after
            // launch; a deletion in that window used to be dropped, and the
            // surviving remote document resurrected the row on the first pull.
            synchronized(pendingDeletesLock) {
                docIds.forEach { pendingDeletes += collection to it }
            }
            return
        }
        docIds.forEach { writer.delete(hid, collection, it) }
    }

    private fun flushPendingDeletes(hid: String) {
        val drained = synchronized(pendingDeletesLock) {
            pendingDeletes.toList().also { pendingDeletes.clear() }
        }
        drained.forEach { (collection, docId) -> writer.delete(hid, collection, docId) }
    }

    private suspend fun runSession(hid: String) = supervisorScope {
        // Parked orphans belong to the previous household's documents.
        applier.reset()
        flushPendingDeletes(hid)
        launch {
            try {
                onSessionStart(hid)
            } catch (e: Exception) {
                android.util.Log.w("SyncEngine", "marking household active failed", e)
            }
        }

        // Snapshots are queued from the moment the session starts. A shared flow
        // with replay = 1 dropped everything emitted while the session was
        // still waiting for its first server snapshot — and since snapshots
        // carry per-snapshot deltas, a lost REMOVED was never applied, so the
        // push mirror re-uploaded the row and undid another device's deletion.
        val streams = APPLY_ORDER.associateWith { collection ->
            Channel<RemoteSnapshot>(Channel.UNLIMITED).also { channel ->
                launch { remote.snapshots(hid, collection).collect { channel.send(it) } }
            }
        }

        // 1) Initial snapshot per collection, awaited in parallel. Server-
        // confirmed only: a cache-served snapshot can be incomplete, and
        // reconcile deletes what it doesn't see.
        val initials = APPLY_ORDER
            .map { collection -> async { collection to streams.getValue(collection).firstFromServer() } }
            .awaitAll()
            .toMap()

        // How much of the local content the reconcile is allowed to delete.
        // First session with a household: everything, which is the documented
        // meaning of joining. Otherwise only rows from the last completed
        // sync — anything newer was written here since (typically offline,
        // where this session can wait for the server indefinitely) and has not
        // provably reached the server, so deleting it would lose it.
        val firstSessionHere = syncState.lastSyncedHouseholdId != hid
        val syncedUpTo = if (firstSessionHere) Long.MAX_VALUE else syncState.lastSyncedAt

        val remoteIsEmpty = initials.values.all { it.docs.isEmpty() }
        if (!remoteIsEmpty) {
            for (collection in APPLY_ORDER) {
                applier.reconcile(collection, initials.getValue(collection).docs, syncedUpTo)
            }
        }
        // Recorded even for an empty household (whose local data seeds it):
        // this device now knows the household, so later sessions must not
        // replace local content wholesale.
        syncState.lastSyncedHouseholdId = hid
        syncState.lastSyncedAt = now()
        _status.value = SyncStatus.Online(now())

        // 2) Ongoing pull: drain each queue in arrival order, so deltas that
        // landed during the wait above are applied rather than lost.
        for (collection in APPLY_ORDER) {
            launch {
                for (snap in streams.getValue(collection)) {
                    applier.apply(collection, snap.upserts, snap.removedIds)
                    // Server-confirmed snapshot = we are demonstrably in sync
                    // now; a cache-only one means Firestore is working from
                    // the local queue (typically: offline).
                    _status.value = if (snap.fromCache) {
                        SyncStatus.Offline((_status.value as? SyncStatus.Online)?.lastSyncAt)
                    } else {
                        SyncStatus.Online(now())
                    }
                }
            }
        }

        // 3) Push mirrors, each seeded with what the server already holds.
        fun seedOf(collection: SyncCollection) = initials.getValue(collection).docs
        mirror(
            hid,
            SyncCollection.PRODUCTS,
            products,
            seedOf(SyncCollection.PRODUCTS),
            { it.id },
            ProductEntity::toSyncDoc,
        )
        mirror(
            hid,
            SyncCollection.LISTS,
            lists,
            seedOf(SyncCollection.LISTS),
            { it.id },
            ShoppingListEntity::toSyncDoc,
        )
        mirror(
            hid,
            SyncCollection.ITEMS,
            items,
            seedOf(SyncCollection.ITEMS),
            { it.id },
            ShoppingListItemEntity::toSyncDoc,
        )
        mirror(
            hid,
            SyncCollection.LIST_ORDER,
            listOrders,
            seedOf(SyncCollection.LIST_ORDER),
            { listOrderDocId(it.listId, it.productId) },
            ProductListOrderEntity::toSyncDoc,
        )
        mirror(
            hid,
            SyncCollection.BARCODES,
            barcodes,
            seedOf(SyncCollection.BARCODES),
            { it.barcode },
            ProductBarcodeEntity::toSyncDoc,
        )
        mirror(
            hid,
            SyncCollection.ONE_OFF_SUGGESTIONS,
            oneOffSuggestions,
            seedOf(SyncCollection.ONE_OFF_SUGGESTIONS),
            { it.id },
            OneOffSuggestionEntity::toSyncDoc,
        )
    }

    /**
     * Mirrors a table to its collection, writing only what the server does not
     * already have.
     *
     * [seed] is the session's first server snapshot, and it is what makes a
     * deletion stick. Starting from an empty cache, the first Room emission
     * re-pushed EVERY local row — so a device that still held a row another
     * device had just deleted wrote it straight back, and the deletion was
     * undone for everyone. (A deletion here is a document disappearing, not a
     * recorded fact: nothing tells this device the row was deleted rather than
     * never known.) Seeded, an unchanged row is recognised as already-present
     * and left alone; when the removal arrives, the pull deletes it locally and
     * the mirror has nothing to resurrect.
     */
    private fun <T> CoroutineScope.mirror(
        hid: String,
        collection: SyncCollection,
        rows: Flow<List<T>>,
        seed: List<RemoteDoc>,
        docId: (T) -> String,
        toDoc: (T) -> Map<String, Any?>,
    ) {
        launch {
            val lastPushed = mutableMapOf<String, Map<String, Any?>>()
            for (doc in seed) lastPushed[doc.id] = comparable(doc.data)
            rows.collect { snapshot ->
                val seen = mutableSetOf<String>()
                for (row in snapshot) {
                    val id = docId(row)
                    seen += id
                    val doc = toDoc(row)
                    val key = comparable(doc)
                    if (lastPushed[id] != key) {
                        writer.set(hid, collection, id, doc)
                        lastPushed[id] = key
                    }
                }
                // Rows gone from Room (deletions went through onDeleted) must
                // not leave stale cache entries, or a re-added row with
                // identical content would be skipped.
                lastPushed.keys.retainAll(seen)
            }
        }
    }

    /**
     * A document in a form that survives the round trip, so a local row can be
     * compared with what the server returned.
     *
     * Firestore has no 32-bit integer: an Int goes up and comes back a Long.
     * Comparing the raw maps would call every row with an amount or a quantity
     * "changed" and push it anyway — the seeding above would look like it
     * worked while doing nothing. Deliberately conservative: only exact matches
     * of everything else count as equal, because a wrongly-equal document
     * SKIPS a push (data lost), while a wrongly-different one merely costs one.
     */
    private fun comparable(data: Map<String, Any?>): Map<String, Any?> =
        data.mapValues { (_, value) -> if (value is Int) value.toLong() else value }

    /** Skips cache-served snapshots; only the server's view may drive a reconcile. */
    private suspend fun Channel<RemoteSnapshot>.firstFromServer(): RemoteSnapshot {
        while (true) {
            val snapshot = receive()
            if (!snapshot.fromCache) return snapshot
        }
    }

    private fun now(): Long = clock.now()

    private companion object {
        // FK parents before children: items and listOrder reference products
        // and lists; barcodes reference products.
        val APPLY_ORDER = listOf(
            SyncCollection.PRODUCTS,
            SyncCollection.LISTS,
            SyncCollection.ITEMS,
            SyncCollection.LIST_ORDER,
            SyncCollection.BARCODES,
            // No foreign keys of its own, so its place in the order is free.
            SyncCollection.ONE_OFF_SUGGESTIONS,
        )
    }
}
