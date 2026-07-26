package io.github.rafalpawlisz.shelfie.data.sync

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
 * Own-write echoes are harmless by design: pulled rows equal their source
 * documents, so the push diff skips them; pushed docs echo back with an
 * equal updatedAt, so the LWW upsert skips those.
 */
class DiffSyncEngine(
    private val householdIds: Flow<String?>,
    private val products: Flow<List<ProductEntity>>,
    private val lists: Flow<List<ShoppingListEntity>>,
    private val items: Flow<List<ShoppingListItemEntity>>,
    private val listOrders: Flow<List<ProductListOrderEntity>>,
    private val barcodes: Flow<List<ProductBarcodeEntity>>,
    private val writer: SyncWriter,
    private val remote: RemoteSource,
    private val applier: SyncApplier,
    private val syncState: SyncStateStore,
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

        // 3) Push mirrors.
        mirror(hid, SyncCollection.PRODUCTS, products, { it.id }, ProductEntity::toSyncDoc)
        mirror(hid, SyncCollection.LISTS, lists, { it.id }, ShoppingListEntity::toSyncDoc)
        mirror(hid, SyncCollection.ITEMS, items, { it.id }, ShoppingListItemEntity::toSyncDoc)
        mirror(
            hid,
            SyncCollection.LIST_ORDER,
            listOrders,
            { listOrderDocId(it.listId, it.productId) },
            ProductListOrderEntity::toSyncDoc,
        )
        mirror(hid, SyncCollection.BARCODES, barcodes, { it.barcode }, ProductBarcodeEntity::toSyncDoc)
    }

    private fun <T> CoroutineScope.mirror(
        hid: String,
        collection: SyncCollection,
        rows: Flow<List<T>>,
        docId: (T) -> String,
        toDoc: (T) -> Map<String, Any?>,
    ) {
        launch {
            val lastPushed = mutableMapOf<String, Map<String, Any?>>()
            rows.collect { snapshot ->
                val seen = mutableSetOf<String>()
                for (row in snapshot) {
                    val id = docId(row)
                    seen += id
                    val doc = toDoc(row)
                    if (lastPushed[id] != doc) {
                        writer.set(hid, collection, id, doc)
                        lastPushed[id] = doc
                    }
                }
                // Rows gone from Room (deletions went through onDeleted) must
                // not leave stale cache entries, or a re-added row with
                // identical content would be skipped.
                lastPushed.keys.retainAll(seen)
            }
        }
    }

    /** Skips cache-served snapshots; only the server's view may drive a reconcile. */
    private suspend fun Channel<RemoteSnapshot>.firstFromServer(): RemoteSnapshot {
        while (true) {
            val snapshot = receive()
            if (!snapshot.fromCache) return snapshot
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        // FK parents before children: items and listOrder reference products
        // and lists; barcodes reference products.
        val APPLY_ORDER = listOf(
            SyncCollection.PRODUCTS,
            SyncCollection.LISTS,
            SyncCollection.ITEMS,
            SyncCollection.LIST_ORDER,
            SyncCollection.BARCODES,
        )
    }
}
