package io.github.rafalpawlisz.shelfie.data.sync

import io.github.rafalpawlisz.shelfie.data.local.ProductBarcodeEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductListOrderEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
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
    private val scope: CoroutineScope,
    // Called once per session so an abandoned household is recognisable
    // later; failures must never take the session down with them.
    private val onSessionStart: suspend (householdId: String) -> Unit = {},
) : SyncEngine {

    // Current household for deletion hooks; null → hooks are no-ops.
    private val activeHousehold = MutableStateFlow<String?>(null)

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
        val hid = activeHousehold.value ?: return
        docIds.forEach { writer.delete(hid, collection, it) }
    }

    private suspend fun runSession(hid: String) = supervisorScope {
        launch {
            try {
                onSessionStart(hid)
            } catch (e: Exception) {
                android.util.Log.w("SyncEngine", "marking household active failed", e)
            }
        }

        val snapshots = SyncCollection.entries.associateWith { collection ->
            remote.snapshots(hid, collection)
                .shareIn(this, SharingStarted.Eagerly, replay = 1)
        }

        // 1) Initial snapshots, parents before children. Server-confirmed
        // only: a cache-served snapshot can be incomplete, and reconcile
        // deletes what it doesn't see.
        val initials = APPLY_ORDER.associateWith { collection ->
            snapshots.getValue(collection).first { !it.fromCache }
        }
        val remoteIsEmpty = initials.values.all { it.docs.isEmpty() }
        if (!remoteIsEmpty) {
            for (collection in APPLY_ORDER) {
                applier.reconcile(collection, initials.getValue(collection).docs)
            }
        }

        // 2) Ongoing pull. The replayed initial goes through apply() too —
        // idempotent after the reconcile, and it seeds the orphan buffer
        // correctly when the reconcile was skipped.
        for (collection in APPLY_ORDER) {
            launch {
                snapshots.getValue(collection).collect { snap ->
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
