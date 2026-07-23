package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingListDao {

    @Query(
        "SELECT i.id AS id, i.productId AS productId, i.amount AS amount, " +
            "i.checkedAt AS checkedAt, p.name AS productName, p.emoji AS productEmoji, " +
            "p.unit AS productUnit " +
            "FROM shopping_list_items i " +
            "JOIN products p ON p.id = i.productId " +
            "WHERE p.archivedAt IS NULL"
    )
    // Ordering (unchecked first, then by name) is applied in the repository
    // with a locale-aware Collator.
    fun observeItems(): Flow<List<ShoppingListItemRow>>

    @Query("SELECT * FROM shopping_list_items WHERE productId = :productId LIMIT 1")
    suspend fun findByProduct(productId: String): ShoppingListItemEntity?

    @Insert
    suspend fun insert(item: ShoppingListItemEntity)

    @Query(
        "UPDATE shopping_list_items SET amount = amount + :extra, updatedAt = :timestamp " +
            "WHERE id = :id"
    )
    suspend fun increaseAmount(id: String, extra: Int, timestamp: Long)

    @Query("DELETE FROM shopping_list_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM shopping_list_items WHERE checkedAt IS NOT NULL")
    suspend fun deleteChecked()

    // Pure in-cart marker: checkedAt = timestamp (in cart) or null (back to buy).
    // No product-quantity side effect — stock is applied only at checkout().
    @Query("UPDATE shopping_list_items SET checkedAt = :checkedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setChecked(id: String, checkedAt: Long?, updatedAt: Long)

    // Adds every checked item's amount to its product. The unique index on
    // productId means the correlated subquery matches at most one row, so no
    // aggregation is needed; the WHERE keeps us from writing NULL into the
    // quantity of products with no checked item. Amounts are > 0, so quantity
    // only grows — no MAX(0, ...) clamp needed.
    @Query(
        "UPDATE products SET quantity = quantity + " +
            "(SELECT i.amount FROM shopping_list_items i " +
            "WHERE i.productId = products.id AND i.checkedAt IS NOT NULL), " +
            "updatedAt = :timestamp " +
            "WHERE id IN (SELECT productId FROM shopping_list_items WHERE checkedAt IS NOT NULL)"
    )
    suspend fun applyCheckedAmountsToProducts(timestamp: Long)

    // "Finish shopping": bank every checked item into its product, then drop
    // the checked rows. Unchecked items remain. Apply-before-delete matters —
    // deleteChecked() removes the rows the subquery reads.
    @Transaction
    suspend fun checkout(timestamp: Long) {
        applyCheckedAmountsToProducts(timestamp)
        deleteChecked()
    }

    @Transaction
    suspend fun addOrMerge(productId: String, amount: Int, newId: String, timestamp: Long) {
        val existing = findByProduct(productId)
        when {
            existing == null -> insert(
                ShoppingListItemEntity(
                    id = newId,
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
