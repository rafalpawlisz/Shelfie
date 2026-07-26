package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.data.local.ProductDao
import io.github.rafalpawlisz.shelfie.data.sync.SyncClock
import io.github.rafalpawlisz.shelfie.data.local.ProductEntity
import io.github.rafalpawlisz.shelfie.data.local.toDomain
import io.github.rafalpawlisz.shelfie.model.Product
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineProductRepository(
    private val dao: ProductDao,
    // Server-corrected: a raw device clock splits a household under LWW.
    private val clock: SyncClock = SyncClock { System.currentTimeMillis() },
) : ProductRepository {

    override fun observeProducts(): Flow<List<Product>> =
        dao.observeActive().map { entities -> entities.map(ProductEntity::toDomain).sortedByName() }

    override fun observeArchivedProducts(): Flow<List<Product>> =
        dao.observeArchived().map { entities -> entities.map(ProductEntity::toDomain).sortedByName() }

    override suspend fun getActiveProduct(id: String): Product? =
        dao.getActive(id)?.toDomain()

    private fun List<Product>.sortedByName(): List<Product> {
        val collator = nameCollator()
        return sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    override suspend fun addProduct(
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int?,
        notes: String?,
        emoji: String?,
    ): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        dao.upsert(
            ProductEntity(
                id = id,
                name = name.trim(),
                quantity = quantity,
                unit = unit,
                updatedAt = now,
                createdAt = now,
                minQuantity = minQuantity,
                notes = notes,
                emoji = emoji,
            )
        )
        return id
    }

    override suspend fun updateProduct(
        id: String,
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int?,
        notes: String?,
        emoji: String?,
    ) {
        dao.update(
            id = id,
            name = name.trim(),
            quantity = quantity,
            unit = unit,
            minQuantity = minQuantity,
            notes = notes,
            emoji = emoji,
            updatedAt = clock.now(),
        )
    }

    override suspend fun adjustQuantity(id: String, delta: Int) {
        dao.adjustQuantity(id = id, delta = delta, updatedAt = clock.now())
    }

    override suspend fun archiveProduct(id: String) {
        dao.archive(id = id, timestamp = clock.now())
    }

    override suspend fun restoreProduct(id: String) {
        dao.restore(id = id, timestamp = clock.now())
    }
}
