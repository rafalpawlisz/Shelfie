package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {

    @Query("SELECT * FROM products WHERE archivedAt IS NULL ORDER BY name COLLATE NOCASE ASC")
    fun observeActive(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE archivedAt IS NOT NULL ORDER BY name COLLATE NOCASE ASC")
    fun observeArchived(): Flow<List<ProductEntity>>

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
            "minQuantity = :minQuantity, notes = :notes, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun update(
        id: String,
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int?,
        notes: String?,
        updatedAt: Long,
    )

    @Query("UPDATE products SET archivedAt = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun archive(id: String, timestamp: Long)

    @Query("UPDATE products SET archivedAt = NULL, updatedAt = :timestamp WHERE id = :id")
    suspend fun restore(id: String, timestamp: Long)
}
