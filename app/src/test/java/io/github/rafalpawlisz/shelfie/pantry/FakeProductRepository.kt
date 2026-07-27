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

    // Sorted and trimmed like the real repository, so a test cannot come to
    // depend on behaviour the app does not have.
    override fun observeProducts(): Flow<List<Product>> =
        entries.map { list -> list.filterNot { it.archived }.map { it.product }.sortedByName() }

    override fun observeArchivedProducts(): Flow<List<Product>> =
        entries.map { list -> list.filter { it.archived }.map { it.product }.sortedByName() }

    private fun List<Product>.sortedByName(): List<Product> =
        sortedWith(compareBy(java.text.Collator.getInstance()) { it.name })

    override suspend fun getActiveProduct(id: String): Product? =
        entries.value.firstOrNull { !it.archived && it.product.id == id }?.product

    override suspend fun addProduct(
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int?,
        notes: String?,
        emoji: String?,
    ): String {
        val id = "id-${nextId++}"
        entries.update {
            it + Entry(
                product = Product(
                    id = id,
                    name = name.trim(),
                    quantity = quantity,
                    unit = unit,
                    minQuantity = minQuantity,
                    notes = notes,
                    emoji = emoji,
                ),
                archived = false,
            )
        }
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

    /**
     * Products the "lists" refer to. The real check is a SQL count over
     * shopping_list_items; a test sets this to say what that count would find.
     */
    var referencedProductIds: Set<String> = emptySet()

    override suspend fun findByName(name: String): Product? {
        val wanted = name.trim()
        return entries.value
            .firstOrNull { it.product.name.trim().equals(wanted, ignoreCase = true) }
            ?.product
    }

    override suspend fun deleteArchivedProduct(id: String): Boolean {
        // Mirrors the DAO's transaction: archived and unreferenced, or nothing
        // happens at all.
        val entry = entries.value.firstOrNull { it.product.id == id } ?: return false
        if (!entry.archived || id in referencedProductIds) return false
        entries.update { list -> list.filterNot { it.product.id == id } }
        return true
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
