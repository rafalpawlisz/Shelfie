package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.data.ShoppingListRepository
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeShoppingListRepository(
    private val products: FakeProductRepository,
) : ShoppingListRepository {

    private data class ListEntry(val id: String, val name: String)

    private data class Item(
        val id: String,
        val listId: String,
        val productId: String,
        val amount: Int,
        val checked: Boolean,
    )

    private val lists = MutableStateFlow<List<ListEntry>>(emptyList())
    private val items = MutableStateFlow<List<Item>>(emptyList())
    private var nextListId = 1
    private var nextId = 1

    override fun observeLists(): Flow<List<ShoppingList>> =
        lists.map { entries ->
            entries.map { ShoppingList(id = it.id, name = it.name) }
                .sortedBy { it.name.lowercase() }
        }

    override suspend fun createList(name: String): String {
        val id = "list-${nextListId++}"
        lists.update { it + ListEntry(id = id, name = name.trim()) }
        return id
    }

    override suspend fun renameList(id: String, name: String) {
        lists.update { list -> list.map { if (it.id == id) it.copy(name = name.trim()) else it } }
    }

    override suspend fun deleteList(id: String) {
        lists.update { list -> list.filterNot { it.id == id } }
        // Mirror the FK CASCADE: dropping a list drops its items.
        items.update { list -> list.filterNot { it.listId == id } }
    }

    override fun observeItems(listId: String): Flow<List<ShoppingListItem>> =
        combine(items, products.observeProducts()) { list, active ->
            list.filter { it.listId == listId }.mapNotNull { item ->
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

    override suspend fun addItem(listId: String, productId: String, amount: Int) {
        val existing = items.value.firstOrNull { it.listId == listId && it.productId == productId }
        when {
            existing == null -> items.update {
                it + Item(
                    id = "item-${nextId++}",
                    listId = listId,
                    productId = productId,
                    amount = amount,
                    checked = false,
                )
            }
            !existing.checked -> items.update { list ->
                list.map { item ->
                    if (item.id == existing.id) item.copy(amount = item.amount + amount) else item
                }
            }
            else -> items.update { list ->
                list.filterNot { it.id == existing.id } +
                    Item(
                        id = "item-${nextId++}",
                        listId = listId,
                        productId = productId,
                        amount = amount,
                        checked = false,
                    )
            }
        }
    }

    override suspend fun setChecked(id: String, checked: Boolean) {
        items.update { list ->
            list.map { if (it.id == id) it.copy(checked = checked) else it }
        }
    }

    override suspend fun removeItem(id: String) {
        items.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun finishShopping(listId: String) {
        items.value.filter { it.listId == listId && it.checked }
            .forEach { products.adjustQuantity(it.productId, it.amount) }
        items.update { list -> list.filterNot { it.listId == listId && it.checked } }
    }
}
