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

    private data class ListEntry(
        val id: String,
        val name: String,
        val position: Double,
        val archivedAt: Long? = null,
    )

    private data class Item(
        val id: String,
        val listId: String,
        val productId: String,
        val amount: Int?,
        val note: String? = null,
        // null = to buy; increasing value = in cart. Monotonic stand-in for the
        // real checkedAt timestamp, so "most recently checked" sorts highest.
        val checkedAt: Long?,
    )

    private val lists = MutableStateFlow<List<ListEntry>>(emptyList())
    private val items = MutableStateFlow<List<Item>>(emptyList())

    // Persistent manual order per (listId, productId). Mirrors product_list_order:
    // it survives removeItem/finishShopping and is cleared only when the list is
    // deleted, so re-adding a product restores its slot.
    private val positions = MutableStateFlow<Map<Pair<String, String>, Double>>(emptyMap())

    private var nextListId = 1
    private var nextId = 1
    private var checkSeq = 0L
    private var archiveSeq = 0L

    override fun observeLists(): Flow<List<ShoppingList>> =
        lists.map { entries -> entries.filter { it.archivedAt == null }.toSortedDomain() }

    override fun observeArchivedLists(): Flow<List<ShoppingList>> =
        lists.map { entries -> entries.filter { it.archivedAt != null }.toSortedDomain() }

    private fun List<ListEntry>.toSortedDomain(): List<ShoppingList> =
        map { ShoppingList(id = it.id, name = it.name, position = it.position) }
            .sortedWith(compareBy({ it.position }, { it.name.lowercase() }))

    override suspend fun createList(name: String): String {
        val id = "list-${nextListId++}"
        val position = (lists.value.maxOfOrNull { it.position } ?: 0.0) + 1.0
        lists.update { it + ListEntry(id = id, name = name.trim(), position = position) }
        return id
    }

    override suspend fun renameList(id: String, name: String) {
        lists.update { list -> list.map { if (it.id == id) it.copy(name = name.trim()) else it } }
    }

    override suspend fun archiveList(id: String) {
        // Soft delete: items and order rows stay put, so restore brings them back.
        lists.update { list -> list.map { if (it.id == id) it.copy(archivedAt = ++archiveSeq) else it } }
    }

    override suspend fun restoreList(id: String) {
        lists.update { list -> list.map { if (it.id == id) it.copy(archivedAt = null) else it } }
    }

    override suspend fun deleteList(id: String) {
        lists.update { list -> list.filterNot { it.id == id } }
        // Mirror the FK CASCADE: dropping a list drops its items and order rows.
        items.update { list -> list.filterNot { it.listId == id } }
        positions.update { map -> map.filterKeys { it.first != id } }
    }

    override suspend fun setListPosition(id: String, position: Double) {
        lists.update { list -> list.map { if (it.id == id) it.copy(position = position) else it } }
    }

    override fun observeItems(listId: String): Flow<List<ShoppingListItem>> =
        combine(items, products.observeProducts(), positions) { list, active, pos ->
            list.filter { it.listId == listId }
                .mapNotNull { item ->
                    val product = active.firstOrNull { it.id == item.productId }
                        ?: return@mapNotNull null
                    item to product
                }
                .sortedWith { (aItem, aProd), (bItem, bProd) ->
                    // Unchecked first (manual position, then name); checked sink to
                    // the bottom ordered by most-recently-checked.
                    val aChecked = aItem.checkedAt != null
                    val bChecked = bItem.checkedAt != null
                    when {
                        aChecked != bChecked -> if (aChecked) 1 else -1
                        aChecked -> bItem.checkedAt!!.compareTo(aItem.checkedAt!!)
                        else -> {
                            val pa = pos[listId to aItem.productId] ?: 0.0
                            val pb = pos[listId to bItem.productId] ?: 0.0
                            val byPos = pa.compareTo(pb)
                            if (byPos != 0) byPos
                            else aProd.name.lowercase().compareTo(bProd.name.lowercase())
                        }
                    }
                }
                .map { (item, product) ->
                    ShoppingListItem(
                        id = item.id,
                        productId = item.productId,
                        amount = item.amount,
                        note = item.note,
                        isChecked = item.checkedAt != null,
                        productName = product.name,
                        productEmoji = product.emoji,
                        productUnit = product.unit,
                        position = pos[listId to item.productId] ?: 0.0,
                    )
                }
        }

    override suspend fun addItem(listId: String, productId: String, amount: Int?, note: String?) {
        ensurePosition(listId, productId)
        val cleanNote = note?.trim()?.ifBlank { null }
        val existing = items.value.firstOrNull { it.listId == listId && it.productId == productId }
        when {
            existing == null -> items.update {
                it + Item(
                    id = "item-${nextId++}",
                    listId = listId,
                    productId = productId,
                    amount = amount,
                    note = cleanNote,
                    checkedAt = null,
                )
            }
            else -> items.update { list ->
                // Mirror the DAO: re-adding replaces amount + note and unchecks.
                list.map { item ->
                    if (item.id == existing.id) {
                        item.copy(amount = amount, note = cleanNote, checkedAt = null)
                    } else {
                        item
                    }
                }
            }
        }
    }

    override suspend fun setChecked(id: String, checked: Boolean) {
        items.update { list ->
            list.map {
                if (it.id == id) it.copy(checkedAt = if (checked) ++checkSeq else null) else it
            }
        }
    }

    override suspend fun setItemAmount(id: String, amount: Int?) {
        items.update { list -> list.map { if (it.id == id) it.copy(amount = amount) else it } }
    }

    override suspend fun setItemDetails(id: String, amount: Int?, note: String?) {
        val cleanNote = note?.trim()?.ifBlank { null }
        items.update { list ->
            list.map { if (it.id == id) it.copy(amount = amount, note = cleanNote) else it }
        }
    }

    override suspend fun removeItem(id: String) {
        // The order row persists (mirrors the real DB), so re-adding restores the slot.
        items.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun finishShopping(listId: String) {
        items.value.filter { it.listId == listId && it.checkedAt != null }
            .forEach { item -> item.amount?.let { products.adjustQuantity(item.productId, it) } }
        // Checked items are removed but their order rows persist.
        items.update { list -> list.filterNot { it.listId == listId && it.checkedAt != null } }
    }

    override suspend fun setItemPosition(listId: String, productId: String, position: Double) {
        positions.update { it + ((listId to productId) to position) }
    }

    override suspend fun isOnAnyList(productId: String): Boolean {
        val activeListIds = lists.value.filter { it.archivedAt == null }.map { it.id }.toSet()
        return items.value.any { it.productId == productId && it.listId in activeListIds }
    }

    override fun observePlannedProductIds(): Flow<List<String>> =
        combine(items, lists) { allItems, allLists ->
            val activeListIds = allLists.filter { it.archivedAt == null }.map { it.id }.toSet()
            allItems.filter { it.listId in activeListIds }.map { it.productId }.distinct()
        }

    // Append at the end the first time a product joins a list; keep an existing slot.
    private fun ensurePosition(listId: String, productId: String) {
        val key = listId to productId
        if (positions.value.containsKey(key)) return
        val max = positions.value.filterKeys { it.first == listId }.values.maxOrNull() ?: 0.0
        positions.update { it + (key to max + 1.0) }
    }
}
