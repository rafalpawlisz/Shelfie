package io.github.rafalpawlisz.shelfie.data.sync

import io.github.rafalpawlisz.shelfie.data.local.ProductBarcodeEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductListOrderEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Push half of the sync: mirrors Room into households/{hid} subcollections.
 *
 * Upserts work by observation — each table's full-content flow is diffed
 * against what was last pushed, so every mutation path in the app is covered
 * without hooks; the first emission after start (or after the household
 * changes) re-pushes everything, which doubles as self-repair. Deletions
 * arrive via [SyncEngine.onDeleted] from the repositories (a snapshot diff
 * can't distinguish "deleted while the engine was down" from "never seen").
 *
 * With no signed-in user or no household the engine idles and the app stays
 * fully local.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiffSyncEngine(
    private val householdIds: Flow<String?>,
    private val products: Flow<List<ProductEntity>>,
    private val lists: Flow<List<ShoppingListEntity>>,
    private val items: Flow<List<ShoppingListItemEntity>>,
    private val listOrders: Flow<List<ProductListOrderEntity>>,
    private val barcodes: Flow<List<ProductBarcodeEntity>>,
    private val writer: SyncWriter,
    private val scope: CoroutineScope,
) : SyncEngine {

    // Current household for deletion hooks; null → hooks are no-ops.
    private val activeHousehold = MutableStateFlow<String?>(null)

    fun start() {
        scope.launch { householdIds.collect { activeHousehold.value = it } }
        mirror(SyncCollection.PRODUCTS, products, { it.id }, ProductEntity::toSyncDoc)
        mirror(SyncCollection.LISTS, lists, { it.id }, ShoppingListEntity::toSyncDoc)
        mirror(SyncCollection.ITEMS, items, { it.id }, ShoppingListItemEntity::toSyncDoc)
        mirror(
            SyncCollection.LIST_ORDER,
            listOrders,
            { listOrderDocId(it.listId, it.productId) },
            ProductListOrderEntity::toSyncDoc,
        )
        mirror(SyncCollection.BARCODES, barcodes, { it.barcode }, ProductBarcodeEntity::toSyncDoc)
    }

    override fun onDeleted(collection: SyncCollection, docIds: List<String>) {
        val hid = activeHousehold.value ?: return
        docIds.forEach { writer.delete(hid, collection, it) }
    }

    private fun <T> mirror(
        collection: SyncCollection,
        rows: Flow<List<T>>,
        docId: (T) -> String,
        toDoc: (T) -> Map<String, Any?>,
    ) {
        scope.launch {
            // Switching household resets the cache: everything gets pushed to
            // the new household on its first emission.
            householdIds.flatMapLatest { hid ->
                if (hid == null) {
                    emptyFlow()
                } else {
                    val lastPushed = mutableMapOf<String, Map<String, Any?>>()
                    rows.map { snapshot -> Triple(hid, snapshot, lastPushed) }
                }
            }.collect { (hid, snapshot, lastPushed) ->
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
                // Rows gone from Room (deletions came through onDeleted) must
                // not leave stale cache entries behind, or a re-added row with
                // identical content would be skipped.
                lastPushed.keys.retainAll(seen)
            }
        }
    }
}
