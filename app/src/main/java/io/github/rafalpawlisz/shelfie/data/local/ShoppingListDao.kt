package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingListDao {

    // --- Lists ---

    @Query("SELECT * FROM shopping_lists") // ordered in the repository (collator)
    fun observeLists(): Flow<List<ShoppingListEntity>>

    @Insert
    suspend fun insertList(list: ShoppingListEntity)

    @Query("UPDATE shopping_lists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameList(id: String, name: String, updatedAt: Long)

    @Query("DELETE FROM shopping_lists WHERE id = :id") // items cascade
    suspend fun deleteList(id: String)

    // --- Items within a list ---

    @Query(
        "SELECT i.id AS id, i.productId AS productId, i.amount AS amount, " +
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

    @Insert
    suspend fun insert(item: ShoppingListItemEntity)

    @Query(
        "UPDATE shopping_list_items SET amount = amount + :extra, updatedAt = :timestamp " +
            "WHERE id = :id"
    )
    suspend fun increaseAmount(id: String, extra: Int, timestamp: Long)

    @Query("DELETE FROM shopping_list_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM shopping_list_items WHERE listId = :listId AND checkedAt IS NOT NULL")
    suspend fun deleteChecked(listId: String)

    // Pure in-cart marker: checkedAt = timestamp (in cart) or null (back to buy).
    // No product-quantity side effect — stock is applied only at checkout().
    @Query("UPDATE shopping_list_items SET checkedAt = :checkedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setChecked(id: String, checkedAt: Long?, updatedAt: Long)

    // Adds every checked item's amount to its product, scoped to one list. The
    // unique (listId, productId) index means the correlated subquery matches at
    // most one row, so no aggregation is needed; the WHERE keeps us from writing
    // NULL into the quantity of products with no checked item. Amounts are > 0,
    // so quantity only grows — no MAX(0, ...) clamp needed.
    @Query(
        "UPDATE products SET quantity = quantity + " +
            "(SELECT i.amount FROM shopping_list_items i " +
            "WHERE i.productId = products.id AND i.checkedAt IS NOT NULL AND i.listId = :listId), " +
            "updatedAt = :timestamp " +
            "WHERE id IN (SELECT productId FROM shopping_list_items " +
            "WHERE checkedAt IS NOT NULL AND listId = :listId)"
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
    suspend fun addOrMerge(listId: String, productId: String, amount: Int, newId: String, timestamp: Long) {
        ensurePosition(listId, productId, timestamp)
        val existing = findByProduct(listId, productId)
        when {
            existing == null -> insert(
                ShoppingListItemEntity(
                    id = newId,
                    listId = listId,
                    productId = productId,
                    amount = amount,
                    checkedAt = null,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
            )
            existing.checkedAt == null -> increaseAmount(existing.id, amount, timestamp)
            else -> {
                // Existing checked entry (in cart, not yet checked out):
                // replace it with a fresh unchecked item at the new amount.
                delete(existing.id)
                insert(
                    ShoppingListItemEntity(
                        id = newId,
                        listId = listId,
                        productId = productId,
                        amount = amount,
                        checkedAt = null,
                        createdAt = timestamp,
                        updatedAt = timestamp,
                    )
                )
            }
        }
    }
}
