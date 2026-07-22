package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.data.local.ProductDao
import io.github.rafalpawlisz.shelfie.data.local.ProductEntity
import io.github.rafalpawlisz.shelfie.data.local.toDomain
import io.github.rafalpawlisz.shelfie.model.Product
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineProductRepository(private val dao: ProductDao) : ProductRepository {

    override fun observeProducts(): Flow<List<Product>> =
        dao.observeAll().map { entities -> entities.map(ProductEntity::toDomain) }

    override suspend fun addProduct(name: String, quantity: Int, unit: String?) {
        dao.upsert(
            ProductEntity(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                quantity = quantity,
                unit = unit,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun adjustQuantity(id: String, delta: Int) {
        dao.adjustQuantity(id = id, delta = delta, updatedAt = System.currentTimeMillis())
    }

    override suspend fun deleteProduct(id: String) {
        dao.deleteById(id)
    }
}
