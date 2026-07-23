package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.data.ProductRepository
import io.github.rafalpawlisz.shelfie.model.Product
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeProductRepository : ProductRepository {

    private data class Entry(val product: Product, val archived: Boolean)

    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private var nextId = 1

    override fun observeProducts(): Flow<List<Product>> =
        entries.map { list -> list.filterNot { it.archived }.map { it.product } }

    override fun observeArchivedProducts(): Flow<List<Product>> =
        entries.map { list -> list.filter { it.archived }.map { it.product } }

    override suspend fun addProduct(
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int?,
        notes: String?,
        emoji: String?,
    ) {
        entries.update {
            it + Entry(
                product = Product(
                    id = "id-${nextId++}",
                    name = name,
                    quantity = quantity,
                    unit = unit,
                    minQuantity = minQuantity,
                    notes = notes,
                    emoji = emoji,
                ),
                archived = false,
            )
        }
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
        mapProduct(id) {
            it.copy(
                name = name.trim(),
                quantity = quantity,
                unit = unit,
                minQuantity = minQuantity,
                notes = notes,
                emoji = emoji,
            )
        }
    }

    override suspend fun adjustQuantity(id: String, delta: Int) {
        mapProduct(id) { it.copy(quantity = (it.quantity + delta).coerceAtLeast(0)) }
    }

    override suspend fun archiveProduct(id: String) {
        setArchived(id, archived = true)
    }

    override suspend fun restoreProduct(id: String) {
        setArchived(id, archived = false)
    }

    private fun mapProduct(id: String, transform: (Product) -> Product) {
        entries.update { list ->
            list.map { entry ->
                if (entry.product.id == id) entry.copy(product = transform(entry.product)) else entry
            }
        }
    }

    private fun setArchived(id: String, archived: Boolean) {
        entries.update { list ->
            list.map { entry ->
                if (entry.product.id == id) entry.copy(archived = archived) else entry
            }
        }
    }
}
