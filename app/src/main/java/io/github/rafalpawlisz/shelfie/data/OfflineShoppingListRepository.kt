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
        dao.observeLists().map { rows ->
            val collator = nameCollator()
            rows.map { ShoppingList(id = it.id, name = it.name) }
                .sortedWith { a, b -> collator.compare(a.name, b.name) }
        }

    override suspend fun createList(name: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insertList(ShoppingListEntity(id = id, name = name.trim(), createdAt = now, updatedAt = now))
        return id
    }

    override suspend fun renameList(id: String, name: String) {
        dao.renameList(id = id, name = name.trim(), updatedAt = System.currentTimeMillis())
    }

    override suspend fun deleteList(id: String) {
        dao.deleteList(id)
    }

    override fun observeItems(listId: String): Flow<List<ShoppingListItem>> =
        dao.observeItems(listId).map { rows ->
            val collator = nameCollator()
            rows.map(ShoppingListItemRow::toDomain).sortedWith { a, b ->
                // Manual order (persisted position); name as a stable, locale-aware
                // tiebreak. Checked items stay in place — no pushing them to the bottom.
                val byPosition = a.position.compareTo(b.position)
                if (byPosition != 0) byPosition else collator.compare(a.productName, b.productName)
            }
        }

    override suspend fun addItem(listId: String, productId: String, amount: Int) {
        dao.addOrMerge(
            listId = listId,
            productId = productId,
            amount = amount,
            newId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
        )
    }

    override suspend fun setChecked(id: String, checked: Boolean) {
        val now = System.currentTimeMillis()
        dao.setChecked(id = id, checkedAt = if (checked) now else null, updatedAt = now)
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
}
