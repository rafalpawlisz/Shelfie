package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.model.Product
import kotlinx.coroutines.flow.Flow

interface ProductRepository {

    fun observeProducts(): Flow<List<Product>>

    fun observeArchivedProducts(): Flow<List<Product>>

    /** One-shot fetch of a single active product, or null if missing/archived. */
    suspend fun getActiveProduct(id: String): Product?

    /** Returns the id of the newly created product. */
    suspend fun addProduct(
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int? = null,
        notes: String? = null,
        emoji: String? = null,
    ): String

    suspend fun updateProduct(
        id: String,
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int? = null,
        notes: String? = null,
        emoji: String? = null,
    )

    suspend fun adjustQuantity(id: String, delta: Int)

    suspend fun archiveProduct(id: String)

    suspend fun restoreProduct(id: String)

    /**
     * Delete an archived product for good, with its barcodes and list-order
     * rows. Returns false and changes nothing when the product is still on a
     * shopping list (archived lists count — they can be restored) or is not
     * archived in the first place.
     */
    suspend fun deleteArchivedProduct(id: String): Boolean
}
