package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.data.local.ShoppingListDao
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemRow
import io.github.rafalpawlisz.shelfie.data.local.toDomain
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineShoppingListRepository(private val dao: ShoppingListDao) : ShoppingListRepository {

    override fun observeItems(): Flow<List<ShoppingListItem>> =
        dao.observeItems().map { rows -> rows.map(ShoppingListItemRow::toDomain) }

    override suspend fun addItem(productId: String, amount: Int) {
        dao.addOrMerge(
            productId = productId,
            amount = amount,
            newId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
        )
    }

    override suspend fun setChecked(id: String, checked: Boolean) {
        dao.setChecked(itemId = id, checked = checked, timestamp = System.currentTimeMillis())
    }

    override suspend fun removeItem(id: String) {
        dao.delete(id)
    }

    override suspend fun clearPurchased() {
        dao.deleteChecked()
    }
}
