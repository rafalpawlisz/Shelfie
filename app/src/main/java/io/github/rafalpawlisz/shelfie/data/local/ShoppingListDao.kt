package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.rafalpawlisz.shelfie.model.ItemSlot
import io.github.rafalpawlisz.shelfie.model.PlannedEntry
import kotlinx.coroutines.flow.Flow

/** Rows a list deletion removed, so the sync layer can mirror the cascade. */
data class DeletedListRows(
    val itemIds: List<String>,
    val orderProductIds: List<String>,
)

@Dao
interface ShoppingListDao {

    // --- Lists ---

    @Query("SELECT * FROM shopping_lists WHERE archivedAt IS NULL") // ordered in the repository (collator)
    fun observeLists(): Flow<List<ShoppingListEntity>>

    @Query("SELECT * FROM shopping_lists WHERE archivedAt IS NOT NULL") // ordered in the repository
    fun observeArchivedLists(): Flow<List<ShoppingListEntity>>

    @Insert
    suspend fun insertList(list: ShoppingListEntity)

    @Query("UPDATE shopping_lists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameList(id: String, name: String, updatedAt: Long)

    // null restores the default aisle order.
    @Query("UPDATE shopping_lists SET sectionOrder = :order, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setSectionOrder(id: String, order: String?, updatedAt: Long)

    // Soft delete: keeps the row (and its items + order) so the list can be restored.
    @Query("UPDATE shopping_lists SET archivedAt = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun archiveList(id: String, timestamp: Long)

    @Query("UPDATE shopping_lists SET archivedAt = NULL, updatedAt = :timestamp WHERE id = :id")
    suspend fun restoreList(id: String, timestamp: Long)

    // Permanent delete (only from the archive view); items and order cascade.
    @Query("DELETE FROM shopping_lists WHERE id = :id")
    suspend fun deleteList(id: String)

    // Manual list order (fractional index); new lists append after the current max.
    @Query("SELECT MAX(position) FROM shopping_lists")
    suspend fun maxListPosition(): Double?

    // Archived lists count as existing: they still hold their items.
    @Query("SELECT EXISTS(SELECT 1 FROM shopping_lists WHERE id = :id)")
    suspend fun listExists(id: String): Boolean

    // --- Sync mirror: full-content flows (archived included) ---

    @Query("SELECT * FROM shopping_lists")
    fun observeAllListRows(): Flow<List<ShoppingListEntity>>

    @Query("SELECT * FROM shopping_list_items")
    fun observeAllItemRows(): Flow<List<ShoppingListItemEntity>>

    @Query("SELECT * FROM product_list_order")
    fun observeAllOrderRows(): Flow<List<ProductListOrderEntity>>

    // --- Sync apply (pull direction) ---

    @Upsert
    suspend fun upsertList(list: ShoppingListEntity)

    @Upsert
    suspend fun upsertItem(item: ShoppingListItemEntity)

    @Upsert
    suspend fun upsertOrderRow(row: ProductListOrderEntity)

    @Query("SELECT updatedAt FROM shopping_lists WHERE id = :id")
    suspend fun listUpdatedAt(id: String): Long?

    @Query("SELECT updatedAt FROM shopping_list_items WHERE id = :id")
    suspend fun itemUpdatedAt(id: String): Long?

    @Query(
        "SELECT updatedAt FROM product_list_order " +
            "WHERE listId = :listId AND productId = :productId"
    )
    suspend fun orderUpdatedAt(listId: String, productId: String): Long?

    // Reconcile candidates: rows already part of a completed sync (see
    // ProductDao.idsSyncedUpTo).
    @Query("SELECT id FROM shopping_lists WHERE updatedAt <= :syncedUpTo")
    suspend fun listIdsSyncedUpTo(syncedUpTo: Long): List<String>

    @Query("SELECT id FROM shopping_list_items WHERE updatedAt <= :syncedUpTo")
    suspend fun itemIdsSyncedUpTo(syncedUpTo: Long): List<String>

    @Query(
        "SELECT listId || '_' || productId FROM product_list_order " +
            "WHERE updatedAt <= :syncedUpTo"
    )
    suspend fun orderKeysSyncedUpTo(syncedUpTo: Long): List<String>

    @Query("DELETE FROM product_list_order WHERE listId = :listId AND productId = :productId")
    suspend fun deleteOrderRow(listId: String, productId: String)

    // --- Sync deletion hooks: what a destructive operation will remove ---

    // Matches what deleteChecked will actually remove, so the sync layer is told
    // about exactly those rows and no others.
    @Query(
        "SELECT id FROM shopping_list_items WHERE listId = :listId AND checkedAt IS NOT NULL " +
            "AND (productId IS NULL " +
            "OR productId IN (SELECT id FROM products WHERE archivedAt IS NULL))"
    )
    suspend fun checkedItemIds(listId: String): List<String>

    @Query("SELECT id FROM shopping_list_items WHERE listId = :listId")
    suspend fun itemIdsOfList(listId: String): List<String>

    @Query("SELECT productId FROM product_list_order WHERE listId = :listId")
    suspend fun orderProductIdsOfList(listId: String): List<String>

    /**
     * Checkout, returning the item ids it removed. One transaction so the ids
     * cannot go stale: an item checked between reading them and the checkout
     * would be banked and deleted without being reported to sync, and the
     * surviving remote document would resurrect it on the next pull.
     */
    @Transaction
    suspend fun checkoutReportingRemoved(listId: String, timestamp: Long): List<String> {
        val removed = checkedItemIds(listId)
        checkout(listId, timestamp)
        return removed
    }

    /** Delete a list, returning what its cascade removed, for the same reason. */
    @Transaction
    suspend fun deleteListReportingRemoved(listId: String): DeletedListRows {
        val rows = DeletedListRows(
            itemIds = itemIdsOfList(listId),
            orderProductIds = orderProductIdsOfList(listId),
        )
        deleteList(listId)
        return rows
    }

    @Query("UPDATE shopping_lists SET position = :position, updatedAt = :timestamp WHERE id = :id")
    suspend fun setListPosition(id: String, position: Double, timestamp: Long)

    // --- Items within a list ---

    // One-off items (productId IS NULL) ride along: their own name stands in
    // for the product's, and their "position" is their creation time — large
    // against hand-assigned fractional indices, so they gather at the end of
    // the unchecked block in the order they were added (they have no slot in
    // product_list_order to drag around).
    @Query(
        "SELECT i.id AS id, i.productId AS productId, i.amount AS amount, i.note AS note, " +
            "i.checkedAt AS checkedAt, COALESCE(p.name, i.name, '') AS productName, " +
            "p.emoji AS productEmoji, " +
            // A one-off's own unit stands in for the product's, like its name.
            "COALESCE(p.unit, i.unit) AS productUnit, COALESCE(o.position, i.position, " +
            "CASE WHEN i.productId IS NULL THEN i.createdAt * 1.0 END, 0.0) AS position, " +
            // The list's aisle order rides along with every row rather than
            // arriving on a second flow: one query is one consistent snapshot,
            // so rows and the order they sort by cannot be a beat apart.
            "l.sectionOrder AS sectionOrder " +
            "FROM shopping_list_items i " +
            "JOIN shopping_lists l ON l.id = i.listId " +
            "LEFT JOIN products p ON p.id = i.productId " +
            "LEFT JOIN product_list_order o ON o.listId = i.listId AND o.productId = i.productId " +
            "WHERE i.listId = :listId AND (i.productId IS NULL OR p.archivedAt IS NULL)"
    )
    // Ordering (manual position, then name) is applied in the repository with a
    // locale-aware Collator.
    fun observeItems(listId: String): Flow<List<ShoppingListItemRow>>

    @Query("SELECT * FROM shopping_list_items WHERE listId = :listId AND productId = :productId LIMIT 1")
    suspend fun findByProduct(listId: String, productId: String): ShoppingListItemEntity?

    @Query("SELECT * FROM shopping_list_items WHERE id = :id")
    suspend fun getById(id: String): ShoppingListItemEntity?

    // Reassign an item to another list; it arrives unchecked (a fresh plan there).
    @Query(
        "UPDATE shopping_list_items SET listId = :listId, checkedAt = NULL, " +
            "updatedAt = :timestamp WHERE id = :id"
    )
    suspend fun reassignList(id: String, listId: String, timestamp: Long)

    // Move an item (amount + note travel along) to another list. The UI blocks
    // targets that already list the product; the same check here is defense in
    // depth — a move never silently clobbers an existing entry (no-op instead).
    // The item's slot on the target comes from its remembered position (or appends).
    @Transaction
    suspend fun moveToList(id: String, targetListId: String, timestamp: Long) {
        val item = getById(id) ?: return
        if (item.listId == targetListId) return
        // A one-off has no product slot to clash with and no order row to
        // reserve — it just changes lists.
        if (item.productId != null) {
            if (findByProduct(targetListId, item.productId) != null) return
            ensurePosition(targetListId, item.productId, timestamp)
        }
        reassignList(item.id, targetListId, timestamp)
    }

    // Is the product waiting to be bought on any non-archived list? Items on
    // archived lists are dormant and don't count as "already planned".
    @Query(
        "SELECT EXISTS(SELECT 1 FROM shopping_list_items i " +
            "JOIN shopping_lists l ON l.id = i.listId " +
            "WHERE i.productId = :productId AND l.archivedAt IS NULL)"
    )
    suspend fun isOnActiveList(productId: String): Boolean

    // Reactive planning map: which products sit on which active lists. Feeds the
    // derived "low stock" list and the move-between-lists guard. One-off items
    // plan no product, so they are not in this map.
    @Query(
        "SELECT i.listId AS listId, i.productId AS productId FROM shopping_list_items i " +
            "JOIN shopping_lists l ON l.id = i.listId " +
            "WHERE l.archivedAt IS NULL AND i.productId IS NOT NULL"
    )
    fun observePlannedEntries(): Flow<List<PlannedEntry>>

    // Products referenced by ANY list, archived ones included — which is what
    // decides whether an archived product may be deleted for good. Archived
    // lists count because restoring one must not find its items missing.
    @Query("SELECT DISTINCT productId FROM shopping_list_items WHERE productId IS NOT NULL")
    fun observeReferencedProductIds(): Flow<List<String>>

    @Insert
    suspend fun insert(item: ShoppingListItemEntity)

    // Direct edit of the amount to buy (> 0 when set, enforced by the dialog and
    // ViewModel; null = "just buy it").
    @Query("UPDATE shopping_list_items SET amount = :amount, updatedAt = :timestamp WHERE id = :id")
    suspend fun setAmount(id: String, amount: Int?, timestamp: Long)

    // The row-tap edit dialog saves amount, unit and note together.
    //
    // The unit is written only where there is no product: for a product row the
    // product's unit is the unit, and the CASE keeps that invariant here rather
    // than trusting every caller to pass null.
    @Query(
        "UPDATE shopping_list_items SET amount = :amount, note = :note, " +
            "unit = CASE WHEN productId IS NULL THEN :unit ELSE unit END, updatedAt = :timestamp " +
            "WHERE id = :id"
    )
    suspend fun setDetails(id: String, amount: Int?, unit: String?, note: String?, timestamp: Long)

    @Query("DELETE FROM shopping_list_items WHERE id = :id")
    suspend fun delete(id: String)

    // Archived products are dormant, exactly like items on an archived list:
    // observeItems hides them, so checkout must not touch them either. Without
    // the filter, stock was banked into a product the user cannot see and the
    // invisible row was deleted — an action they could neither watch nor undo.
    // A checked one-off (productId IS NULL) has no stock to bank and simply
    // leaves with the shopping trip.
    @Query(
        "DELETE FROM shopping_list_items WHERE listId = :listId AND checkedAt IS NOT NULL " +
            "AND (productId IS NULL " +
            "OR productId IN (SELECT id FROM products WHERE archivedAt IS NULL))"
    )
    suspend fun deleteChecked(listId: String)

    // Pure in-cart marker: checkedAt = timestamp (in cart) or null (back to buy).
    // No product-quantity side effect — stock is applied only at checkout().
    @Query("UPDATE shopping_list_items SET checkedAt = :checkedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setChecked(id: String, checkedAt: Long?, updatedAt: Long)

    // Adds every checked item's amount to its product, scoped to one list. The
    // unique (listId, productId) index means the correlated subquery matches at
    // most one row, so no aggregation is needed; the WHERE keeps us from writing
    // NULL into the quantity of products with no checked item. Amounts are > 0,
    // so quantity only grows — no MAX(0, ...) clamp needed. Checked items
    // normally have an amount (the check-off dialog asks for it); the
    // amount IS NOT NULL guards are defensive — such items just don't touch stock.
    @Query(
        "UPDATE products SET quantity = quantity + " +
            "(SELECT i.amount FROM shopping_list_items i " +
            "WHERE i.productId = products.id AND i.checkedAt IS NOT NULL " +
            "AND i.amount IS NOT NULL AND i.listId = :listId), " +
            "updatedAt = :timestamp " +
            "WHERE archivedAt IS NULL AND id IN (SELECT productId FROM shopping_list_items " +
            "WHERE checkedAt IS NOT NULL AND amount IS NOT NULL AND listId = :listId)"
    )
    suspend fun applyCheckedAmountsToProducts(listId: String, timestamp: Long)

    // "Finish shopping": bank every checked item of this list into its product,
    // then drop the checked rows. Unchecked items remain. Apply-before-delete
    // matters — deleteChecked() removes the rows the subquery reads.
    @Transaction
    suspend fun checkout(listId: String, timestamp: Long) {
        applyCheckedAmountsToProducts(listId, timestamp)
        deleteChecked(listId)
    }

    // --- Manual order (persisted per list+product, survives item removal) ---

    // First time a product joins a list, append it at the end; an existing slot
    // is left untouched (INSERT OR IGNORE), so re-adding a product keeps its
    // place. Positions are never deleted on checkout/removal, only by cascade.
    @Query(
        "INSERT OR IGNORE INTO product_list_order (listId, productId, position, updatedAt) " +
            "VALUES (:listId, :productId, " +
            "COALESCE((SELECT MAX(position) FROM product_list_order WHERE listId = :listId), 0.0) + 1.0, " +
            ":timestamp)"
    )
    suspend fun ensurePosition(listId: String, productId: String, timestamp: Long)

    @Query(
        "UPDATE product_list_order SET position = :position, updatedAt = :timestamp " +
            "WHERE listId = :listId AND productId = :productId"
    )
    suspend fun setPosition(listId: String, productId: String, position: Double, timestamp: Long)

    // A one-off's slot lives on its own row (it has no product to key an order
    // row by, and nothing to remember after checkout). The productId guard keeps
    // this away from product rows, whose slot is product_list_order's business.
    @Query(
        "UPDATE shopping_list_items SET position = :position, updatedAt = :timestamp " +
            "WHERE id = :id AND productId IS NULL"
    )
    suspend fun setOneOffPosition(id: String, position: Double, timestamp: Long)

    /**
     * An aisle's new order, written in one transaction: a product's slot into its
     * order row, a one-off's onto the row itself. All or nothing, so a half-
     * renumbered aisle is never observable.
     */
    @Transaction
    suspend fun setPositions(listId: String, slots: List<ItemSlot>, timestamp: Long) {
        for (slot in slots) {
            if (slot.productId == null) {
                setOneOffPosition(slot.itemId, slot.position, timestamp)
            } else {
                // The order row exists from the moment the product joined the
                // list, but a pull from another device could have removed it;
                // ensure it rather than silently dropping the reorder.
                ensurePosition(listId, slot.productId, timestamp)
                setPosition(listId, slot.productId, slot.position, timestamp)
            }
        }
    }

    @Transaction
    suspend fun addOrMerge(
        listId: String,
        productId: String,
        amount: Int?,
        note: String?,
        newId: String,
        timestamp: Long,
    ) {
        ensurePosition(listId, productId, timestamp)
        val existing = findByProduct(listId, productId)
        when {
            existing == null -> insert(
                ShoppingListItemEntity(
                    id = newId,
                    listId = listId,
                    productId = productId,
                    name = null,
                    amount = amount,
                    // The product's unit is the unit.
                    unit = null,
                    note = note,
                    checkedAt = null,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
            )
            else -> {
                // Adding an already-listed product REPLACES its amount and note —
                // the add dialog pre-fills the current values, so what's confirmed
                // is what you get (no merge math). A checked entry goes back to
                // the to-buy state.
                setDetails(existing.id, amount, unit = null, note = note, timestamp = timestamp)
                setChecked(existing.id, checkedAt = null, updatedAt = timestamp)
            }
        }
    }
}
