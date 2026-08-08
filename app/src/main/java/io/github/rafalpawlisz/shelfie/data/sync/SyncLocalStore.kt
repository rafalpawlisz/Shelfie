package io.github.rafalpawlisz.shelfie.data.sync

import android.database.SQLException
import android.util.Log
import io.github.rafalpawlisz.shelfie.data.local.OneOffSuggestionDao
import io.github.rafalpawlisz.shelfie.data.local.OneOffSuggestionEntity
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

    /** The write failed for a reason retrying will not fix; logged and dropped. */
    FAILED,
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
    private val suggestionDao: OneOffSuggestionDao,
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
            SyncCollection.ONE_OFF_SUGGESTIONS -> suggestionDao.updatedAtOf(docId)
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
                    val item = itemFrom(docId, data) ?: return UpsertResult.MALFORMED
                    resolveItemSlotClash(item)?.let { return it }
                    shoppingListDao.upsertItem(item)
                }
                SyncCollection.BARCODES -> {
                    barcodeDao.insert(barcodeFrom(docId, data) ?: return UpsertResult.MALFORMED)
                }
                SyncCollection.ONE_OFF_SUGGESTIONS -> {
                    suggestionDao.upsert(
                        suggestionFrom(docId, data) ?: return UpsertResult.MALFORMED,
                    )
                }
                SyncCollection.LIST_ORDER -> {
                    shoppingListDao.upsertOrderRow(orderFrom(data) ?: return UpsertResult.MALFORMED)
                }
            }
            UpsertResult.APPLIED
        } catch (e: SQLException) {
            // Only a missing FK parent is worth retrying (its snapshot just
            // hasn't arrived yet). Treating every SQLException as such — as
            // this did — parks unfixable rows in the orphan buffer forever,
            // retrying them on every snapshot for the rest of the session.
            if (e.message?.contains("FOREIGN KEY", ignoreCase = true) == true) {
                UpsertResult.MISSING_PARENT
            } else {
                Log.w(TAG, "sync upsert failed: ${collection.path}/$docId", e)
                UpsertResult.FAILED
            }
        }
    }

    /**
     * Two devices adding the same product to the same list each mint their own
     * item id, so one slot ends up with two documents — and the unique
     * (listId, productId) index makes the second one fail to insert.
     *
     * Resolved here rather than left to the constraint: the newer write wins,
     * with the smaller id breaking exact ties, so every device independently
     * converges on the same survivor. Returns null when there is no clash (the
     * caller proceeds), or the result to report when the incoming doc loses.
     *
     * The losing document stays in Firestore, where it will lose this
     * comparison forever — harmless, but it does mean a slot can resurrect if
     * the winner is later deleted.
     */
    private suspend fun resolveItemSlotClash(item: ShoppingListItemEntity): UpsertResult? {
        // One-off items occupy no product slot — NULLs are distinct under the
        // unique index, so there is nothing to clash with.
        val productId = item.productId ?: return null
        val clash = shoppingListDao.findByProduct(item.listId, productId)
            ?: return null
        if (clash.id == item.id) return null
        val incomingWins = item.updatedAt > clash.updatedAt ||
            (item.updatedAt == clash.updatedAt && item.id < clash.id)
        if (!incomingWins) return UpsertResult.SKIPPED_OLDER
        shoppingListDao.delete(clash.id)
        return null
    }

    override suspend fun delete(collection: SyncCollection, docId: String) {
        when (collection) {
            SyncCollection.PRODUCTS -> productDao.deleteById(docId)
            SyncCollection.LISTS -> shoppingListDao.deleteList(docId)
            SyncCollection.ITEMS -> shoppingListDao.delete(docId)
            SyncCollection.BARCODES -> barcodeDao.delete(docId)
            SyncCollection.ONE_OFF_SUGGESTIONS -> suggestionDao.delete(docId)
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
        SyncCollection.ONE_OFF_SUGGESTIONS -> suggestionDao.idsSyncedUpTo(syncedUpTo)
        SyncCollection.LIST_ORDER -> shoppingListDao.orderKeysSyncedUpTo(syncedUpTo)
    }

    private fun suggestionFrom(id: String, d: Map<String, Any?>): OneOffSuggestionEntity? =
        OneOffSuggestionEntity(
            id = id,
            name = d.string("name") ?: return null,
            unit = d.string("unit"),
            lastUsedAt = d.long("lastUsedAt") ?: return null,
            updatedAt = d.long("updatedAt") ?: return null,
        )

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
            expiresOn = d.string("expiresOn"),
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
            sectionOrder = d.string("sectionOrder"),
        )
    }

    private fun itemFrom(id: String, d: Map<String, Any?>): ShoppingListItemEntity? {
        val productId = d.string("productId")
        val name = d.string("name")
        // A row must have something to show: a product to join or its own
        // name (a one-off). A document with neither is malformed.
        if (productId == null && name == null) return null
        return ShoppingListItemEntity(
            id = id,
            listId = d.string("listId") ?: return null,
            productId = productId,
            name = name,
            amount = d.int("amount"),
            unit = d.string("unit"),
            position = d.double("position"),
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

    private companion object {
        const val TAG = "SyncEngine"
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
