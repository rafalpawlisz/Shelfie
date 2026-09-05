package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.data.ShoppingListRepository
import io.github.rafalpawlisz.shelfie.data.local.sectionEmojiFor
import io.github.rafalpawlisz.shelfie.model.PlannedEntry
import io.github.rafalpawlisz.shelfie.model.ItemSlot
import io.github.rafalpawlisz.shelfie.model.OneOffSuggestion
import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.model.ProductCategory
import io.github.rafalpawlisz.shelfie.model.SectionOrder
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.rankOf
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
        // Stored the way the real column is: comma-separated names, null = default.
        val sectionOrder: String? = null,
    )

    private data class Item(
        val id: String,
        val listId: String,
        // null = one-off item; [name] carries its display name instead.
        val productId: String?,
        val name: String? = null,
        val amount: Int?,
        // A one-off's own unit; product rows read theirs off the product.
        val unit: String? = null,
        // A one-off's own manual slot, once dragged; null until then.
        val position: Double? = null,
        // A one-off's hand-picked section: null = nobody picked one and the
        // name answers, "" = somebody picked "no section".
        val sectionEmoji: String? = null,
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

    // Remembered one-off names paired with a sequence, newest first on read —
    // the fake stand-in for lastUsedAt.
    private val suggestions = MutableStateFlow<List<Pair<OneOffSuggestion, Long>>>(emptyList())
    private var suggestionSeq = 0L

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
        map {
            ShoppingList(
                id = it.id,
                name = it.name,
                position = it.position,
                sectionOrder = SectionOrder.parse(it.sectionOrder),
            )
        }
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

    override suspend fun setSectionOrder(listId: String, order: List<ProductCategory>) {
        val stored = SectionOrder.store(order)
        lists.update { list ->
            list.map { if (it.id == listId) it.copy(sectionOrder = stored) else it }
        }
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

    override fun observeListItemCounts(): Flow<Map<String, Int>> =
        combine(items, lists, products.observeProducts()) { allItems, allLists, active ->
            // Mirrors the DAO: visible items on active lists only — dormant
            // items of archived products don't count, and neither do archived
            // lists. Lists with zero visible items get no entry.
            val activeListIds = allLists.filter { it.archivedAt == null }.map { it.id }.toSet()
            allItems
                .filter {
                    it.listId in activeListIds &&
                        (it.productId == null || active.any { product -> product.id == it.productId })
                }
                .groupingBy { it.listId }
                .eachCount()
        }

    override fun observeItems(listId: String): Flow<List<ShoppingListItem>> =
        observeItemsWith(listId, products.observeProducts())

    override fun observeItemsIncludingDormant(listId: String): Flow<List<ShoppingListItem>> =
        observeItemsWith(
            listId,
            // Archived products join too: their rows are dormant (invisible on
            // the list) but the picker still pre-fills from them.
            combine(
                products.observeProducts(),
                products.observeArchivedProducts(),
            ) { active, archived -> active + archived },
        )

    private fun observeItemsWith(
        listId: String,
        productSource: Flow<List<Product>>,
    ): Flow<List<ShoppingListItem>> =
        combine(items, productSource, positions, lists) { list, productList, pos, allLists ->
            // Mirrors the DAO's COALESCE: the product's slot, or a one-off's own
            // slot once dragged, or — for a one-off nobody placed — creation
            // order, which lands it at the end of the unchecked block.
            fun manualPositionOf(item: Item): Double? =
                if (item.productId == null) item.position else pos[listId to item.productId]
            fun positionOf(item: Item): Double =
                manualPositionOf(item)
                    ?: if (item.productId == null) ONE_OFF_BASE + item.seq else 0.0
            // Mirrors the join on shopping_lists: this list's own aisle order.
            val order = SectionOrder.parse(
                allLists.firstOrNull { it.id == listId }?.sectionOrder,
            )
            list.filter { it.listId == listId }
                .mapNotNull { item ->
                    if (item.productId == null) return@mapNotNull item to null
                    // A product outside the source (archived, when only active
                    // ones flow) drops the row — the real visibility filter.
                    val product = productList.firstOrNull { it.id == item.productId }
                        ?: return@mapNotNull null
                    item to product
                }
                .sortedWith { (aItem, aProd), (bItem, bProd) ->
                    // Mirrors the real repository: unchecked walk the store
                    // section by section (sectionless trailing), manual position
                    // then name inside one; checked sink to the bottom ordered
                    // by most-recently-checked, sections ignored.
                    val aChecked = aItem.checkedAt != null
                    val bChecked = bItem.checkedAt != null
                    when {
                        aChecked != bChecked -> if (aChecked) 1 else -1
                        aChecked -> bItem.checkedAt!!.compareTo(aItem.checkedAt!!)
                        else -> {
                            val bySection = order.rankOf(
                                sectionOf(
                                    aItem.productId,
                                    aProd?.emoji,
                                    aProd?.name ?: aItem.name.orEmpty(),
                                    aItem.sectionEmoji,
                                ),
                            ).compareTo(
                                order.rankOf(
                                    sectionOf(
                                        bItem.productId,
                                        bProd?.emoji,
                                        bProd?.name ?: bItem.name.orEmpty(),
                                        bItem.sectionEmoji,
                                    ),
                                ),
                            )
                            if (bySection != 0) return@sortedWith bySection
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
                        productEmoji = sectionEmojiFor(
                            item.productId,
                            product?.emoji,
                            product?.name ?: item.name.orEmpty(),
                            item.sectionEmoji,
                        ),
                        sectionEmoji = item.sectionEmoji,
                        // Mirrors the DAO's COALESCE: a one-off's own unit
                        // stands in for the product's.
                        productUnit = product?.unit ?: item.unit,
                        position = positionOf(item),
                    )
                }
        }

    override suspend fun addItem(
        listId: String,
        productId: String,
        amount: Int?,
        note: String?,
    ): String {
        // Mirror the real DAO's foreign keys: Room throws for a list or
        // product that does not exist (SQLiteConstraintException) instead of
        // writing a ghost row — a fake that accepted one would pass tests the
        // app would crash on.
        check(lists.value.any { it.id == listId }) { "no such list: $listId" }
        check(products.containsProduct(productId)) { "no such product: $productId" }
        ensurePosition(listId, productId)
        val cleanNote = note?.trim()?.ifBlank { null }
        val existing = items.value.firstOrNull { it.listId == listId && it.productId == productId }
        return when {
            existing == null -> {
                val id = "item-${nextId++}"
                items.update {
                    it + Item(
                        id = id,
                        listId = listId,
                        productId = productId,
                        amount = amount,
                        note = cleanNote,
                        checkedAt = null,
                    )
                }
                id
            }
            else -> {
                // Mirror the DAO: re-adding replaces amount + note and unchecks.
                items.update { list ->
                    list.map { item ->
                        if (item.id == existing.id) {
                            item.copy(amount = amount, note = cleanNote, checkedAt = null)
                        } else {
                            item
                        }
                    }
                }
                existing.id
            }
        }
    }

    override fun observeOneOffSuggestions(): Flow<List<OneOffSuggestion>> =
        suggestions.map { list ->
            list.sortedByDescending { it.second }.map { (suggestion, _) -> suggestion }
        }

    override suspend fun forgetOneOffSuggestion(name: String) {
        val key = name.trim().lowercase()
        suggestions.update { list -> list.filterNot { it.first.name.trim().lowercase() == key } }
    }

    override suspend fun addOneOffItem(
        listId: String,
        name: String,
        amount: Int?,
        unit: String?,
        note: String?,
        sectionEmoji: String?,
    ): String {
        // Same FK mirror as addItem: a one-off line writes into a list that
        // must exist.
        check(lists.value.any { it.id == listId }) { "no such list: $listId" }
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        // Mirrors the repository: the word is remembered even though the line
        // below will not survive checkout. Keyed case-insensitively, so buying
        // the same thing again moves the entry up rather than duplicating it.
        val cleanUnit = unit?.trim()?.ifBlank { null }
        val key = trimmed.lowercase()
        suggestions.update { list ->
            list.filterNot { it.first.name.trim().lowercase() == key } +
                (OneOffSuggestion(name = trimmed, unit = cleanUnit) to ++suggestionSeq)
        }
        // Mirrors the DAO: a plain insert, never a merge — one-offs occupy no
        // product slot, so repeats of the same name are separate lines.
        val id = "item-${nextId++}"
        items.update {
            it + Item(
                id = id,
                listId = listId,
                productId = null,
                name = trimmed,
                amount = amount,
                unit = unit?.trim()?.ifBlank { null },
                // Not blank-collapsed like the rest: "" is a choice here.
                sectionEmoji = sectionEmoji,
                note = note?.trim()?.ifBlank { null },
                checkedAt = null,
                seq = ++oneOffSeq,
            )
        }
        return id
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

    override suspend fun setItemDetails(
        id: String,
        amount: Int?,
        unit: String?,
        note: String?,
        sectionEmoji: String?,
    ) {
        val cleanNote = note?.trim()?.ifBlank { null }
        val cleanUnit = unit?.trim()?.ifBlank { null }
        items.update { list ->
            list.map {
                if (it.id != id) {
                    it
                } else {
                    // Mirrors the DAO's CASE: only a one-off takes a unit or a
                    // section from here; a product row keeps its product's.
                    it.copy(
                        amount = amount,
                        unit = if (it.productId == null) cleanUnit else it.unit,
                        sectionEmoji =
                            if (it.productId == null) sectionEmoji else it.sectionEmoji,
                        note = cleanNote,
                    )
                }
            }
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
                if (it.id == id) {
                    // Mirrors reassignList: arrives unchecked, and a one-off's
                    // slot stays behind — it meant "between these neighbours"
                    // on the old list.
                    it.copy(listId = targetListId, checkedAt = null, position = null)
                } else {
                    it
                }
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

    override suspend fun setItemPositions(listId: String, slots: List<ItemSlot>) {
        // Mirrors the DAO: a product's slot into the order map, a one-off's onto
        // the row itself — and never the other way round.
        positions.update { map ->
            map + slots.mapNotNull { slot ->
                slot.productId?.let { (listId to it) to slot.position }
            }
        }
        val byId = slots.filter { it.productId == null }.associate { it.itemId to it.position }
        items.update { list ->
            list.map { item ->
                val slot = byId[item.id]
                if (slot != null && item.productId == null) item.copy(position = slot) else item
            }
        }
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

        // Resolved the same way the row is shown: a product's own section, a
        // one-off's pick, or its name. The list's order turns it into a rank.
        fun sectionOf(
            productId: String?,
            emoji: String?,
            name: String,
            chosen: String?,
        ): ProductCategory? =
            ProductCategory.fromEmoji(sectionEmojiFor(productId, emoji, name, chosen))
    }

    // Append at the end the first time a product joins a list; keep an existing slot.
    private fun ensurePosition(listId: String, productId: String) {
        val key = listId to productId
        if (positions.value.containsKey(key)) return
        val max = positions.value.filterKeys { it.first == listId }.values.maxOrNull() ?: 0.0
        positions.update { it + (key to max + 1.0) }
    }
}
