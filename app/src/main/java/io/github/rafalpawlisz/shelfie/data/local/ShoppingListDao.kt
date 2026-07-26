package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
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
            "AND productId IN (SELECT id FROM products WHERE archivedAt IS NULL)"
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

    @Query(
        "SELECT i.id AS id, i.productId AS productId, i.amount AS amount, i.note AS note, " +
            "i.checkedAt AS checkedAt, p.name AS productName, p.emoji AS productEmoji, " +
            "p.unit AS productUnit, COALESCE(o.position, 0.0) AS position " +
            "FROM shopping_list_items i " +
            "JOIN products p ON p.id = i.productId " +
            "LEFT JOIN product_list_order o ON o.listId = i.listId AND o.productId = i.productId " +
            "WHERE i.listId = :listId AND p.archivedAt IS NULL"
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
        if (findByProduct(targetListId, item.productId) != null) return
        ensurePosition(targetListId, item.productId, timestamp)
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
    // derived "low stock" list and the move-between-lists guard.
    @Query(
        "SELECT i.listId AS listId, i.productId AS productId FROM shopping_list_items i " +
            "JOIN shopping_lists l ON l.id = i.listId WHERE l.archivedAt IS NULL"
    )
    fun observePlannedEntries(): Flow<List<PlannedEntry>>

    @Insert
    suspend fun insert(item: ShoppingListItemEntity)

    // Direct edit of the amount to buy (> 0 when set, enforced by the dialog and
    // ViewModel; null = "just buy it").
    @Query("UPDATE shopping_list_items SET amount = :amount, updatedAt = :timestamp WHERE id = :id")
    suspend fun setAmount(id: String, amount: Int?, timestamp: Long)

    // The row-tap edit dialog saves amount and note together.
    @Query(
        "UPDATE shopping_list_items SET amount = :amount, note = :note, updatedAt = :timestamp " +
            "WHERE id = :id"
    )
    suspend fun setDetails(id: String, amount: Int?, note: String?, timestamp: Long)

    @Query("DELETE FROM shopping_list_items WHERE id = :id")
    suspend fun delete(id: String)

    // Archived products are dormant, exactly like items on an archived list:
    // observeItems hides them, so checkout must not touch them either. Without
    // the filter, stock was banked into a product the user cannot see and the
    // invisible row was deleted — an action they could neither watch nor undo.
    @Query(
        "DELETE FROM shopping_list_items WHERE listId = :listId AND checkedAt IS NOT NULL " +
            "AND productId IN (SELECT id FROM products WHERE archivedAt IS NULL)"
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
                    amount = amount,
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
                setDetails(existing.id, amount, note, timestamp)
                setChecked(existing.id, checkedAt = null, updatedAt = timestamp)
            }
        }
    }
}
