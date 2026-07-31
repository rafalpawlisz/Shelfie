package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.data.ShoppingListRepository
import io.github.rafalpawlisz.shelfie.model.PlannedEntry
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
        // null = one-off item; [name] carries its display name instead.
        val productId: String?,
        val name: String? = null,
        val amount: Int?,
        val note: String? = null,
        // null = to buy; increasing value = in cart. Monotonic stand-in for the
        // real checkedAt timestamp, so "most recently checked" sorts highest.
        val checkedAt: Long?,
        // Stand-in for createdAt: one-offs sort at the end, in add order,
        // mirroring the DAO's createdAt-as-position fallback.
        val seq: Long = 0,
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
    private var oneOffSeq = 0L

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
            // Mirrors the DAO's position fallback: one-offs have no manual
            // slot and sort at the end of the unchecked block, in add order.
            fun positionOf(item: Item): Double =
                if (item.productId == null) ONE_OFF_BASE + item.seq
                else pos[listId to item.productId] ?: 0.0
            list.filter { it.listId == listId }
                .mapNotNull { item ->
                    if (item.productId == null) return@mapNotNull item to null
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
                            val byPos = positionOf(aItem).compareTo(positionOf(bItem))
                            if (byPos != 0) byPos
                            else (aProd?.name ?: aItem.name).orEmpty().lowercase()
                                .compareTo((bProd?.name ?: bItem.name).orEmpty().lowercase())
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
                        productName = product?.name ?: item.name.orEmpty(),
                        productEmoji = product?.emoji,
                        productUnit = product?.unit,
                        position = positionOf(item),
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

    override suspend fun addOneOffItem(listId: String, name: String, amount: Int?, note: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        // Mirrors the DAO: a plain insert, never a merge — one-offs occupy no
        // product slot, so repeats of the same name are separate lines.
        items.update {
            it + Item(
                id = "item-${nextId++}",
                listId = listId,
                productId = null,
                name = trimmed,
                amount = amount,
                note = note?.trim()?.ifBlank { null },
                checkedAt = null,
                seq = ++oneOffSeq,
            )
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

    override suspend fun moveItem(id: String, targetListId: String) {
        val item = items.value.firstOrNull { it.id == id } ?: return
        if (item.listId == targetListId) return
        // Mirror the DAO: never clobber an existing entry on the target list;
        // one-offs occupy no slot and move freely.
        if (item.productId != null) {
            val targetHasProduct = items.value.any {
                it.listId == targetListId && it.productId == item.productId
            }
            if (targetHasProduct) return
            ensurePosition(targetListId, item.productId)
        }
        items.update { list ->
            list.map {
                if (it.id == id) it.copy(listId = targetListId, checkedAt = null) else it
            }
        }
    }

    override suspend fun removeItem(id: String) {
        // The order row persists (mirrors the real DB), so re-adding restores the slot.
        items.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun finishShopping(listId: String) {
        // Items of archived products are dormant (observeItems hides them), so
        // checkout skips them exactly as the DAO does. Checked one-offs have
        // no stock to bank — they are simply removed.
        val processed = items.value
            .filter { it.listId == listId && it.checkedAt != null }
            .filter { it.productId == null || products.getActiveProduct(it.productId) != null }
        processed.forEach { item ->
            if (item.productId != null) {
                item.amount?.let { products.adjustQuantity(item.productId, it) }
            }
        }
        // Checked items are removed but their order rows persist.
        val processedIds = processed.map { it.id }.toSet()
        items.update { list -> list.filterNot { it.id in processedIds } }
    }

    override suspend fun setItemPosition(listId: String, productId: String, position: Double) {
        positions.update { it + ((listId to productId) to position) }
    }

    // Mirrors the DAO: archived lists still exist (they keep their items).
    override suspend fun listExists(id: String): Boolean = lists.value.any { it.id == id }

    override suspend fun isOnAnyList(productId: String): Boolean {
        val activeListIds = lists.value.filter { it.archivedAt == null }.map { it.id }.toSet()
        return items.value.any { it.productId == productId && it.listId in activeListIds }
    }

    // Every list, archived included — the real query only drops one-offs.
    override fun observeReferencedProductIds(): Flow<List<String>> =
        items.map { all -> all.mapNotNull { it.productId }.distinct() }

    override fun observePlannedEntries(): Flow<List<PlannedEntry>> =
        combine(items, lists) { allItems, allLists ->
            val activeListIds = allLists.filter { it.archivedAt == null }.map { it.id }.toSet()
            allItems.filter { it.listId in activeListIds && it.productId != null }
                .map { PlannedEntry(listId = it.listId, productId = it.productId!!) }
        }

    private companion object {
        // Far above any hand-assigned fractional index, like the DAO's
        // createdAt-in-millis fallback.
        const val ONE_OFF_BASE = 1_000_000.0
    }

    // Append at the end the first time a product joins a list; keep an existing slot.
    private fun ensurePosition(listId: String, productId: String) {
        val key = listId to productId
        if (positions.value.containsKey(key)) return
        val max = positions.value.filterKeys { it.first == listId }.values.maxOrNull() ?: 0.0
        positions.update { it + (key to max + 1.0) }
    }
}
