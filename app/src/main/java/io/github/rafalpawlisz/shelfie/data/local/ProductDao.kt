package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {

    // Ordering is done in the repository with a locale-aware Collator
    // (SQLite COLLATE NOCASE is ASCII-only and misplaces Polish letters).
    @Query("SELECT * FROM products WHERE archivedAt IS NULL")
    fun observeActive(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE archivedAt IS NOT NULL")
    fun observeArchived(): Flow<List<ProductEntity>>

    // Sync mirror: every row, archived included.
    @Query("SELECT * FROM products")
    fun observeAllRows(): Flow<List<ProductEntity>>

    // --- Sync apply (pull direction) ---

    @Query("SELECT updatedAt FROM products WHERE id = :id")
    suspend fun updatedAtOf(id: String): Long?

    // Reconcile candidates: rows that were already part of a completed sync.
    // Anything newer is local work that may not have reached the server yet,
    // and deleting it would lose data.
    @Query("SELECT id FROM products WHERE updatedAt <= :syncedUpTo")
    suspend fun idsSyncedUpTo(syncedUpTo: Long): List<String>

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteById(id: String)

    // --- Deleting an archived product for good ---

    /**
     * Every shopping-list item pointing at this product, archived lists
     * included. An archived list can be restored, so a reference from one is
     * still a reference: deleting the product would quietly remove the item
     * (the FK cascades) and change a list nobody touched.
     */
    @Query("SELECT COUNT(*) FROM shopping_list_items WHERE productId = :id")
    suspend fun listItemCount(id: String): Int

    @Query("SELECT barcode FROM product_barcodes WHERE productId = :id")
    suspend fun barcodesOf(id: String): List<String>

    @Query("SELECT listId FROM product_list_order WHERE productId = :id")
    suspend fun orderedListIdsOf(id: String): List<String>

    @Query("SELECT id FROM products WHERE id = :id AND archivedAt IS NOT NULL")
    suspend fun archivedIdOf(id: String): String?

    /**
     * Delete an archived, unused product together with what belongs to it,
     * and report the child rows so the same deletions can be mirrored.
     *
     * Returns null when the product must not go: not archived (deleting
     * something still in the pantry is not what any caller means) or still
     * referenced by a list. The check lives here rather than only in the UI
     * because the UI reads a snapshot — the other device can add the product
     * to a list between the button appearing and the tap.
     *
     * Barcodes and order rows are read BEFORE the delete, since the cascade
     * removes them and their ids would be gone with them.
     */
    @Transaction
    suspend fun deleteArchivedIfUnused(id: String): DeletedProductRows? {
        if (archivedIdOf(id) == null) return null
        if (listItemCount(id) > 0) return null
        val rows = DeletedProductRows(
            barcodes = barcodesOf(id),
            orderedListIds = orderedListIdsOf(id),
        )
        deleteById(id)
        return rows
    }

    @Query("SELECT * FROM products WHERE id = :id AND archivedAt IS NULL")
    suspend fun getActive(id: String): ProductEntity?

    // Name lookups are done in Kotlin: SQLite's NOCASE is ASCII-only, so it
    // would consider "Żurawina" and "żurawina" different products.
    @Query("SELECT * FROM products")
    suspend fun getAll(): List<ProductEntity>

    @Upsert
    suspend fun upsert(product: ProductEntity)

    // Atomic update: clamping at 0 happens in SQL, so concurrent taps
    // can't race a read-modify-write cycle.
    @Query(
        "UPDATE products SET quantity = MAX(0, quantity + :delta), updatedAt = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun adjustQuantity(id: String, delta: Int, updatedAt: Long)

    @Query(
        "UPDATE products SET name = :name, quantity = :quantity, unit = :unit, " +
            "minQuantity = :minQuantity, notes = :notes, emoji = :emoji, " +
            "expiresOn = :expiresOn, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun update(
        id: String,
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int?,
        notes: String?,
        emoji: String?,
        expiresOn: String?,
        updatedAt: Long,
    )

    @Query("UPDATE products SET archivedAt = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun archive(id: String, timestamp: Long)

    @Query("UPDATE products SET archivedAt = NULL, updatedAt = :timestamp WHERE id = :id")
    suspend fun restore(id: String, timestamp: Long)
}

/** What [ProductDao.deleteArchivedIfUnused] took with the product. */
data class DeletedProductRows(
    val barcodes: List<String>,
    val orderedListIds: List<String>,
)
