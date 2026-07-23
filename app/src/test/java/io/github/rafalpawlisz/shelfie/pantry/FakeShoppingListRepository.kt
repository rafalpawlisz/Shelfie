package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.data.ShoppingListRepository
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

class FakeShoppingListRepository(
    private val products: FakeProductRepository,
) : ShoppingListRepository {

    private data class Item(
        val id: String,
        val productId: String,
        val amount: Int,
        val checked: Boolean,
    )

    private val items = MutableStateFlow<List<Item>>(emptyList())
    private var nextId = 1

    override fun observeItems(): Flow<List<ShoppingListItem>> =
        combine(items, products.observeProducts()) { list, active ->
            list.mapNotNull { item ->
                val product = active.firstOrNull { it.id == item.productId }
                    ?: return@mapNotNull null
                ShoppingListItem(
                    id = item.id,
                    productId = item.productId,
                    amount = item.amount,
                    isChecked = item.checked,
                    productName = product.name,
                    productEmoji = product.emoji,
                    productUnit = product.unit,
                )
            }.sortedWith(compareBy({ it.isChecked }, { it.productName.lowercase() }))
        }

    override suspend fun addItem(productId: String, amount: Int) {
        val existing = items.value.firstOrNull { it.productId == productId }
        when {
            existing == null -> items.update {
                it + Item(id = "item-${nextId++}", productId = productId, amount = amount, checked = false)
            }
            !existing.checked -> items.update { list ->
                list.map { item ->
                    if (item.id == existing.id) item.copy(amount = item.amount + amount) else item
                }
            }
            else -> items.update { list ->
                list.filterNot { it.id == existing.id } +
                    Item(id = "item-${nextId++}", productId = productId, amount = amount, checked = false)
            }
        }
    }

    override suspend fun setChecked(id: String, checked: Boolean) {
        val item = items.value.firstOrNull { it.id == id } ?: return
        if (item.checked == checked) return
        items.update { list ->
            list.map { if (it.id == id) it.copy(checked = checked) else it }
        }
        products.adjustQuantity(item.productId, if (checked) item.amount else -item.amount)
    }

    override suspend fun removeItem(id: String) {
        items.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun clearPurchased() {
        items.update { list -> list.filterNot { it.checked } }
    }
}
