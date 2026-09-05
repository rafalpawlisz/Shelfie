package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.data.local.OneOffSuggestionDao
import io.github.rafalpawlisz.shelfie.data.local.OneOffSuggestionEntity
import io.github.rafalpawlisz.shelfie.data.local.oneOffSuggestionId
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListDao
import io.github.rafalpawlisz.shelfie.data.sync.NoopSyncEngine
import io.github.rafalpawlisz.shelfie.data.sync.SyncClock
import io.github.rafalpawlisz.shelfie.data.sync.SyncCollection
import io.github.rafalpawlisz.shelfie.data.sync.SyncEngine
import io.github.rafalpawlisz.shelfie.data.sync.listOrderDocId
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemRow
import io.github.rafalpawlisz.shelfie.data.local.sectionEmojiFor
import io.github.rafalpawlisz.shelfie.data.local.toDomain
import io.github.rafalpawlisz.shelfie.model.ItemSlot
import io.github.rafalpawlisz.shelfie.model.OneOffSuggestion
import io.github.rafalpawlisz.shelfie.model.PlannedEntry
import io.github.rafalpawlisz.shelfie.model.ProductCategory
import io.github.rafalpawlisz.shelfie.model.SectionOrder
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.rankOf
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineShoppingListRepository(
    private val dao: ShoppingListDao,
    // The vocabulary of things bought once, which outlives the lines themselves.
    private val suggestionDao: OneOffSuggestionDao,
    // Deletions must be reported at mutation time (the sync engine's snapshot
    // diff can't see them across restarts); upserts need no hook.
    private val sync: SyncEngine = NoopSyncEngine,
    private val clock: SyncClock = SyncClock { System.currentTimeMillis() },
) : ShoppingListRepository {

    override fun observeLists(): Flow<List<ShoppingList>> =
        dao.observeLists().map { it.toSortedDomain() }

    override fun observeArchivedLists(): Flow<List<ShoppingList>> =
        dao.observeArchivedLists().map { it.toSortedDomain() }

    override fun observeListItemCounts(): Flow<Map<String, Int>> =
        dao.observeListItemCounts().map { rows -> rows.associate { it.listId to it.itemCount } }

    private fun List<ShoppingListEntity>.toSortedDomain(): List<ShoppingList> {
        val collator = nameCollator()
        return map {
            ShoppingList(
                id = it.id,
                name = it.name,
                position = it.position,
                sectionOrder = SectionOrder.parse(it.sectionOrder),
            )
        }
            .sortedWith { a, b ->
                // Manual order; name as a stable, locale-aware tiebreak.
                val byPosition = a.position.compareTo(b.position)
                if (byPosition != 0) byPosition else collator.compare(a.name, b.name)
            }
    }

    override suspend fun createList(name: String): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val position = (dao.maxListPosition() ?: 0.0) + 1.0
        dao.insertList(
            ShoppingListEntity(
                id = id,
                name = name.trim(),
                createdAt = now,
                updatedAt = now,
                position = position,
            ),
        )
        return id
    }

    override suspend fun renameList(id: String, name: String) {
        dao.renameList(id = id, name = name.trim(), updatedAt = clock.now())
    }

    override suspend fun setSectionOrder(listId: String, order: List<ProductCategory>) {
        dao.setSectionOrder(
            id = listId,
            order = SectionOrder.store(order),
            updatedAt = clock.now(),
        )
    }

    override suspend fun archiveList(id: String) {
        dao.archiveList(id, clock.now())
    }

    override suspend fun restoreList(id: String) {
        dao.restoreList(id, clock.now())
    }

    override suspend fun deleteList(id: String) {
        // The DAO captures what the FK cascade removes inside the transaction;
        // reading it separately could miss rows added in between.
        val removed = dao.deleteListReportingRemoved(id)
        sync.onDeleted(SyncCollection.ITEMS, removed.itemIds)
        sync.onDeleted(
            SyncCollection.LIST_ORDER,
            removed.orderProductIds.map { listOrderDocId(id, it) },
        )
        sync.onDeleted(SyncCollection.LISTS, listOf(id))
    }

    override suspend fun setListPosition(id: String, position: Double) {
        dao.setListPosition(id, position, clock.now())
    }

    override fun observeItems(listId: String): Flow<List<ShoppingListItem>> =
        dao.observeItems(listId).map { rows -> sortedItems(rows) }

    override fun observeItemsIncludingDormant(listId: String): Flow<List<ShoppingListItem>> =
        dao.observeItemsIncludingDormant(listId).map { rows -> sortedItems(rows) }

    private fun sortedItems(rows: List<ShoppingListItemRow>): List<ShoppingListItem> {
        val collator = nameCollator()
        // Every row carries the same list's order; no rows, nothing to sort.
        val order = SectionOrder.parse(rows.firstOrNull()?.sectionOrder)
        // Unchecked (still to buy) first, walked store section by store
        // section in the global aisle order; within a section the manual
        // position, then name. Sectionless rows — one-offs, emoji from
        // before sections, none — form the trailing group. Checked items
        // sink to the bottom ordered by most-recently-checked, sections
        // ignored: what is in the cart has no aisle anymore. Sorting on
        // rows lets us read checkedAt (the domain model only keeps the flag).
        return rows.sortedWith { a, b ->
            val aChecked = a.checkedAt != null
            val bChecked = b.checkedAt != null
            when {
                aChecked != bChecked -> if (aChecked) 1 else -1
                aChecked -> {
                    val byTime = b.checkedAt!!.compareTo(a.checkedAt)
                    if (byTime != 0) byTime else collator.compare(a.productName, b.productName)
                }
                else -> {
                    val bySection = order.rankOf(a.section())
                        .compareTo(order.rankOf(b.section()))
                    if (bySection != 0) return@sortedWith bySection
                    val byPosition = a.position.compareTo(b.position)
                    if (byPosition != 0) byPosition else collator.compare(a.productName, b.productName)
                }
            }
        }.map(ShoppingListItemRow::toDomain)
    }

    // Resolved the same way the row is shown, so a one-off sorts into the aisle
    // it displays — whether that was picked by hand or read from its name.
    private fun ShoppingListItemRow.section(): ProductCategory? =
        ProductCategory.fromEmoji(
            sectionEmojiFor(productId, productEmoji, productName, itemSectionEmoji),
        )

    override suspend fun addItem(
        listId: String,
        productId: String,
        amount: Int?,
        note: String?,
    ): String = dao.addOrMerge(
        listId = listId,
        productId = productId,
        amount = amount,
        note = note?.trim()?.ifBlank { null },
        newId = UUID.randomUUID().toString(),
        timestamp = clock.now(),
    )

    override suspend fun addOneOffItem(
        listId: String,
        name: String,
        amount: Int?,
        unit: String?,
        note: String?,
        sectionEmoji: String?,
    ): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val cleanUnit = unit?.trim()?.ifBlank { null }
        // Remember the word. The line below will be gone after checkout; this
        // is what lets the picker offer the name again next November.
        suggestionDao.upsert(
            OneOffSuggestionEntity(
                id = oneOffSuggestionId(trimmed),
                name = trimmed,
                unit = cleanUnit,
                lastUsedAt = now,
                updatedAt = now,
            ),
        )
        // A plain insert, no merge: one-offs occupy no product slot (NULLs are
        // distinct under the unique index), so two bulbs are two lines — which
        // is what a hand-written list would say too.
        dao.insert(
            ShoppingListItemEntity(
                id = id,
                listId = listId,
                productId = null,
                name = trimmed,
                amount = amount,
                unit = cleanUnit,
                note = note?.trim()?.ifBlank { null },
                sectionEmoji = sectionEmoji,
                checkedAt = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    override suspend fun setChecked(id: String, checked: Boolean) {
        val now = clock.now()
        dao.setChecked(id = id, checkedAt = if (checked) now else null, updatedAt = now)
    }

    override suspend fun setItemAmount(id: String, amount: Int?) {
        dao.setAmount(id, amount, clock.now())
    }

    override suspend fun setItemDetails(
        id: String,
        amount: Int?,
        unit: String?,
        note: String?,
        sectionEmoji: String?,
    ) {
        dao.setDetails(
            id = id,
            amount = amount,
            unit = unit?.trim()?.ifBlank { null },
            note = note?.trim()?.ifBlank { null },
            // Not blank-collapsed like the others: "" is a choice here, and
            // turning it into null would turn "no section" back into "guess".
            sectionEmoji = sectionEmoji,
            timestamp = clock.now(),
        )
    }

    override suspend fun moveItem(id: String, targetListId: String) {
        dao.moveToList(id, targetListId, clock.now())
    }

    override suspend fun removeItem(id: String) {
        dao.delete(id)
        sync.onDeleted(SyncCollection.ITEMS, listOf(id))
    }

    override suspend fun finishShopping(listId: String) {
        val removed = dao.checkoutReportingRemoved(listId, clock.now())
        sync.onDeleted(SyncCollection.ITEMS, removed)
    }

    override suspend fun setItemPositions(listId: String, slots: List<ItemSlot>) {
        dao.setPositions(listId, slots, clock.now())
    }

    override suspend fun listExists(id: String): Boolean = dao.listExists(id)

    override suspend fun isOnAnyList(productId: String): Boolean = dao.isOnActiveList(productId)

    override fun observeOneOffSuggestions(): Flow<List<OneOffSuggestion>> =
        suggestionDao.observeAll().map { rows -> rows.map(OneOffSuggestionEntity::toDomain) }

    override suspend fun forgetOneOffSuggestion(name: String) {
        suggestionDao.delete(oneOffSuggestionId(name))
        sync.onDeleted(SyncCollection.ONE_OFF_SUGGESTIONS, listOf(oneOffSuggestionId(name)))
    }

    override fun observePlannedEntries(): Flow<List<PlannedEntry>> = dao.observePlannedEntries()

    override fun observeReferencedProductIds(): Flow<List<String>> =
        dao.observeReferencedProductIds()
}
