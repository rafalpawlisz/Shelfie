package io.github.rafalpawlisz.shelfie.data.sync

import android.database.SQLException
import io.github.rafalpawlisz.shelfie.data.local.ProductBarcodeDao
import io.github.rafalpawlisz.shelfie.data.local.ProductBarcodeEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductDao
import io.github.rafalpawlisz.shelfie.data.local.ProductEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductListOrderEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListDao
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemEntity

enum class UpsertResult {
    APPLIED,

    /** The local row is at least as new — last write wins, remote loses. */
    SKIPPED_OLDER,

    /** The document doesn't parse into a row; drop it. */
    MALFORMED,

    /** FK parent not present (yet) — the applier buffers and retries. */
    MISSING_PARENT,
}

/**
 * The pull direction's write surface over Room. Last-write-wins lives here:
 * an upsert applies only when the remote updatedAt is strictly newer than
 * the local row's — echoes of our own pushes carry an equal timestamp and
 * fall out as SKIPPED_OLDER.
 */
interface SyncLocalStore {
    suspend fun upsert(collection: SyncCollection, docId: String, data: Map<String, Any?>): UpsertResult
    suspend fun delete(collection: SyncCollection, docId: String)

    /**
     * Ids of rows whose updatedAt is at or before [syncedUpTo] — the rows a
     * reconcile may delete. Rows written after that point have not provably
     * reached the server, so their absence remotely means "not pushed yet",
     * not "deleted elsewhere". Pass [Long.MAX_VALUE] to consider every row.
     */
    suspend fun idsSyncedUpTo(collection: SyncCollection, syncedUpTo: Long): List<String>
}

class RoomSyncLocalStore(
    private val productDao: ProductDao,
    private val shoppingListDao: ShoppingListDao,
    private val barcodeDao: ProductBarcodeDao,
) : SyncLocalStore {

    override suspend fun upsert(
        collection: SyncCollection,
        docId: String,
        data: Map<String, Any?>,
    ): UpsertResult {
        val remoteUpdatedAt = data.long("updatedAt") ?: return UpsertResult.MALFORMED
        val localUpdatedAt = when (collection) {
            SyncCollection.PRODUCTS -> productDao.updatedAtOf(docId)
            SyncCollection.LISTS -> shoppingListDao.listUpdatedAt(docId)
            SyncCollection.ITEMS -> shoppingListDao.itemUpdatedAt(docId)
            SyncCollection.BARCODES -> barcodeDao.updatedAtOf(docId)
            SyncCollection.LIST_ORDER -> {
                val listId = data.string("listId") ?: return UpsertResult.MALFORMED
                val productId = data.string("productId") ?: return UpsertResult.MALFORMED
                shoppingListDao.orderUpdatedAt(listId, productId)
            }
        }
        if (localUpdatedAt != null && localUpdatedAt >= remoteUpdatedAt) {
            return UpsertResult.SKIPPED_OLDER
        }
        return try {
            when (collection) {
                SyncCollection.PRODUCTS -> {
                    productDao.upsert(productFrom(docId, data) ?: return UpsertResult.MALFORMED)
                }
                SyncCollection.LISTS -> {
                    shoppingListDao.upsertList(listFrom(docId, data) ?: return UpsertResult.MALFORMED)
                }
                SyncCollection.ITEMS -> {
                    shoppingListDao.upsertItem(itemFrom(docId, data) ?: return UpsertResult.MALFORMED)
                }
                SyncCollection.BARCODES -> {
                    barcodeDao.insert(barcodeFrom(docId, data) ?: return UpsertResult.MALFORMED)
                }
                SyncCollection.LIST_ORDER -> {
                    shoppingListDao.upsertOrderRow(orderFrom(data) ?: return UpsertResult.MALFORMED)
                }
            }
            UpsertResult.APPLIED
        } catch (_: SQLException) {
            // The only constraint a well-formed sync row can trip is a
            // missing FK parent (its snapshot just hasn't arrived yet).
            UpsertResult.MISSING_PARENT
        }
    }

    override suspend fun delete(collection: SyncCollection, docId: String) {
        when (collection) {
            SyncCollection.PRODUCTS -> productDao.deleteById(docId)
            SyncCollection.LISTS -> shoppingListDao.deleteList(docId)
            SyncCollection.ITEMS -> shoppingListDao.delete(docId)
            SyncCollection.BARCODES -> barcodeDao.delete(docId)
            SyncCollection.LIST_ORDER -> {
                val (listId, productId) = splitOrderKey(docId) ?: return
                shoppingListDao.deleteOrderRow(listId, productId)
            }
        }
    }

    override suspend fun idsSyncedUpTo(
        collection: SyncCollection,
        syncedUpTo: Long,
    ): List<String> = when (collection) {
        SyncCollection.PRODUCTS -> productDao.idsSyncedUpTo(syncedUpTo)
        SyncCollection.LISTS -> shoppingListDao.listIdsSyncedUpTo(syncedUpTo)
        SyncCollection.ITEMS -> shoppingListDao.itemIdsSyncedUpTo(syncedUpTo)
        SyncCollection.BARCODES -> barcodeDao.barcodesSyncedUpTo(syncedUpTo)
        SyncCollection.LIST_ORDER -> shoppingListDao.orderKeysSyncedUpTo(syncedUpTo)
    }

    private fun productFrom(id: String, d: Map<String, Any?>): ProductEntity? {
        return ProductEntity(
            id = id,
            name = d.string("name") ?: return null,
            quantity = d.int("quantity") ?: return null,
            unit = d.string("unit"),
            updatedAt = d.long("updatedAt") ?: return null,
            archivedAt = d.long("archivedAt"),
            createdAt = d.long("createdAt") ?: 0,
            minQuantity = d.int("minQuantity"),
            notes = d.string("notes"),
            emoji = d.string("emoji"),
        )
    }

    private fun listFrom(id: String, d: Map<String, Any?>): ShoppingListEntity? {
        return ShoppingListEntity(
            id = id,
            name = d.string("name") ?: return null,
            createdAt = d.long("createdAt") ?: 0,
            updatedAt = d.long("updatedAt") ?: return null,
            position = d.double("position") ?: return null,
            archivedAt = d.long("archivedAt"),
        )
    }

    private fun itemFrom(id: String, d: Map<String, Any?>): ShoppingListItemEntity? {
        return ShoppingListItemEntity(
            id = id,
            listId = d.string("listId") ?: return null,
            productId = d.string("productId") ?: return null,
            amount = d.int("amount"),
            note = d.string("note"),
            checkedAt = d.long("checkedAt"),
            createdAt = d.long("createdAt") ?: 0,
            updatedAt = d.long("updatedAt") ?: return null,
        )
    }

    private fun barcodeFrom(barcode: String, d: Map<String, Any?>): ProductBarcodeEntity? {
        return ProductBarcodeEntity(
            barcode = barcode,
            productId = d.string("productId") ?: return null,
            createdAt = d.long("createdAt") ?: 0,
            updatedAt = d.long("updatedAt") ?: return null,
        )
    }

    private fun orderFrom(d: Map<String, Any?>): ProductListOrderEntity? {
        return ProductListOrderEntity(
            listId = d.string("listId") ?: return null,
            productId = d.string("productId") ?: return null,
            position = d.double("position") ?: return null,
            updatedAt = d.long("updatedAt") ?: return null,
        )
    }

    // UUIDs contain no underscore, so the first '_' splits a listOrder key.
    private fun splitOrderKey(key: String): Pair<String, String>? {
        val index = key.indexOf('_')
        if (index <= 0 || index == key.length - 1) return null
        return key.substring(0, index) to key.substring(index + 1)
    }
}

// Firestore hands numbers back as Long or Double regardless of what went in.
private fun Map<String, Any?>.string(key: String): String? = this[key] as? String
private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
private fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
