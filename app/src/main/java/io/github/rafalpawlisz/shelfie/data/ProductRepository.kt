package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.model.Product
import kotlinx.coroutines.flow.Flow

interface ProductRepository {

    fun observeProducts(): Flow<List<Product>>

    fun observeArchivedProducts(): Flow<List<Product>>

    suspend fun addProduct(name: String, quantity: Int, unit: String?)

    suspend fun updateProduct(id: String, name: String, quantity: Int, unit: String?)

    suspend fun adjustQuantity(id: String, delta: Int)

    suspend fun archiveProduct(id: String)

    suspend fun restoreProduct(id: String)
}
