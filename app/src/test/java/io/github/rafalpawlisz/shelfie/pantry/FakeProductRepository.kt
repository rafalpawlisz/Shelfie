package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.data.ProductRepository
import io.github.rafalpawlisz.shelfie.model.Product
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeProductRepository : ProductRepository {

    private val products = MutableStateFlow<List<Product>>(emptyList())
    private var nextId = 1

    override fun observeProducts(): Flow<List<Product>> = products

    override suspend fun addProduct(name: String, quantity: Int, unit: String?) {
        products.update { it + Product(id = "id-${nextId++}", name = name, quantity = quantity, unit = unit) }
    }

    override suspend fun updateProduct(id: String, name: String, quantity: Int, unit: String?) {
        products.update { list ->
            list.map { product ->
                if (product.id == id) {
                    product.copy(name = name.trim(), quantity = quantity, unit = unit)
                } else {
                    product
                }
            }
        }
    }

    override suspend fun adjustQuantity(id: String, delta: Int) {
        products.update { list ->
            list.map { product ->
                if (product.id == id) {
                    product.copy(quantity = (product.quantity + delta).coerceAtLeast(0))
                } else {
                    product
                }
            }
        }
    }

    override suspend fun deleteProduct(id: String) {
        products.update { list -> list.filterNot { it.id == id } }
    }
}
