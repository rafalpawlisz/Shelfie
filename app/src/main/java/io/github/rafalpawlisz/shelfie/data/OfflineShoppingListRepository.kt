package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.data.local.ShoppingListDao
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemRow
import io.github.rafalpawlisz.shelfie.data.local.toDomain
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineShoppingListRepository(private val dao: ShoppingListDao) : ShoppingListRepository {

    override fun observeLists(): Flow<List<ShoppingList>> =
        dao.observeLists().map { it.toSortedDomain() }

    override fun observeArchivedLists(): Flow<List<ShoppingList>> =
        dao.observeArchivedLists().map { it.toSortedDomain() }

    private fun List<ShoppingListEntity>.toSortedDomain(): List<ShoppingList> {
        val collator = nameCollator()
        return map { ShoppingList(id = it.id, name = it.name, position = it.position) }
            .sortedWith { a, b ->
                // Manual order; name as a stable, locale-aware tiebreak.
                val byPosition = a.position.compareTo(b.position)
                if (byPosition != 0) byPosition else collator.compare(a.name, b.name)
            }
    }

    override suspend fun createList(name: String): String {
        val now = System.currentTimeMillis()
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
        dao.renameList(id = id, name = name.trim(), updatedAt = System.currentTimeMillis())
    }

    override suspend fun archiveList(id: String) {
        dao.archiveList(id, System.currentTimeMillis())
    }

    override suspend fun restoreList(id: String) {
        dao.restoreList(id, System.currentTimeMillis())
    }

    override suspend fun deleteList(id: String) {
        dao.deleteList(id)
    }

    override suspend fun setListPosition(id: String, position: Double) {
        dao.setListPosition(id, position, System.currentTimeMillis())
    }

    override fun observeItems(listId: String): Flow<List<ShoppingListItem>> =
        dao.observeItems(listId).map { rows ->
            val collator = nameCollator()
            // Unchecked (still to buy) first, in manual position order; checked
            // items sink to the bottom ordered by most-recently-checked. Sorting
            // on rows lets us read checkedAt (the domain model only keeps the flag).
            rows.sortedWith { a, b ->
                val aChecked = a.checkedAt != null
                val bChecked = b.checkedAt != null
                when {
                    aChecked != bChecked -> if (aChecked) 1 else -1
                    aChecked -> {
                        val byTime = b.checkedAt!!.compareTo(a.checkedAt!!)
                        if (byTime != 0) byTime else collator.compare(a.productName, b.productName)
                    }
                    else -> {
                        val byPosition = a.position.compareTo(b.position)
                        if (byPosition != 0) byPosition else collator.compare(a.productName, b.productName)
                    }
                }
            }.map(ShoppingListItemRow::toDomain)
        }

    override suspend fun addItem(listId: String, productId: String, amount: Int?, note: String?) {
        dao.addOrMerge(
            listId = listId,
            productId = productId,
            amount = amount,
            note = note?.trim()?.ifBlank { null },
            newId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
        )
    }

    override suspend fun setChecked(id: String, checked: Boolean) {
        val now = System.currentTimeMillis()
        dao.setChecked(id = id, checkedAt = if (checked) now else null, updatedAt = now)
    }

    override suspend fun setItemAmount(id: String, amount: Int?) {
        dao.setAmount(id, amount, System.currentTimeMillis())
    }

    override suspend fun setItemDetails(id: String, amount: Int?, note: String?) {
        dao.setDetails(id, amount, note?.trim()?.ifBlank { null }, System.currentTimeMillis())
    }

    override suspend fun removeItem(id: String) {
        dao.delete(id)
    }

    override suspend fun finishShopping(listId: String) {
        dao.checkout(listId, System.currentTimeMillis())
    }

    override suspend fun setItemPosition(listId: String, productId: String, position: Double) {
        dao.setPosition(listId, productId, position, System.currentTimeMillis())
    }

    override suspend fun isOnAnyList(productId: String): Boolean = dao.isOnActiveList(productId)
}
