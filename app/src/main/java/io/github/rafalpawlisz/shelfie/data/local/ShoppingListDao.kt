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
            "WHERE p.archivedAt IS NULL " +
            "ORDER BY (i.checkedAt IS NOT NULL) ASC, p.name COLLATE NOCASE ASC"
    )
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

    // Guarded flips: the state predicate makes a repeated identical toggle a
    // no-op. The Int result (rows affected) gates the quantity side effect.
    @Query(
        "UPDATE shopping_list_items SET checkedAt = :timestamp, updatedAt = :timestamp " +
            "WHERE id = :id AND checkedAt IS NULL"
    )
    suspend fun markChecked(id: String, timestamp: Long): Int

    @Query(
        "UPDATE shopping_list_items SET checkedAt = NULL, updatedAt = :timestamp " +
            "WHERE id = :id AND checkedAt IS NOT NULL"
    )
    suspend fun markUnchecked(id: String, timestamp: Long): Int

    // sign = +1 (checked: bought) or -1 (unchecked: reverted).
    @Query(
        "UPDATE products SET quantity = MAX(0, quantity + :sign * " +
            "(SELECT amount FROM shopping_list_items WHERE id = :itemId)), " +
            "updatedAt = :timestamp " +
            "WHERE id = (SELECT productId FROM shopping_list_items WHERE id = :itemId)"
    )
    suspend fun applyItemAmountToProduct(itemId: String, sign: Int, timestamp: Long)

    @Transaction
    suspend fun setChecked(itemId: String, checked: Boolean, timestamp: Long) {
        val flipped =
            if (checked) markChecked(itemId, timestamp) else markUnchecked(itemId, timestamp)
        if (flipped == 1) {
            applyItemAmountToProduct(
                itemId = itemId,
                sign = if (checked) 1 else -1,
                timestamp = timestamp,
            )
        }
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
                // Stale checked entry from a past trip: its quantity effect
                // stays applied; replace it with a fresh unchecked item.
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
