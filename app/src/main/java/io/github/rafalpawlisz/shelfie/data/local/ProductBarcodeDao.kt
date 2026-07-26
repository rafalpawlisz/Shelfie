package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductBarcodeDao {

    @Query("SELECT * FROM product_barcodes ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProductBarcodeEntity>>

    // REPLACE: scanning a code already mapped to another product reassigns it
    // to the current one (same physical article — last scan wins).
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ProductBarcodeEntity)

    @Query("SELECT productId FROM product_barcodes WHERE barcode = :barcode")
    suspend fun findProductId(barcode: String): String?

    @Query("DELETE FROM product_barcodes WHERE barcode = :barcode")
    suspend fun delete(barcode: String)

    // --- Sync apply (pull direction) ---

    @Query("SELECT updatedAt FROM product_barcodes WHERE barcode = :barcode")
    suspend fun updatedAtOf(barcode: String): Long?

    @Query("SELECT barcode FROM product_barcodes")
    suspend fun allBarcodes(): List<String>
}
