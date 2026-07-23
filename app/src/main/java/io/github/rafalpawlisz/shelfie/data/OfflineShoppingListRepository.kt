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
        dao.observeItems().map { rows ->
            val collator = nameCollator()
            rows.map(ShoppingListItemRow::toDomain).sortedWith { a, b ->
                // Unchecked (to buy) first, then name — locale-aware.
                val byChecked = a.isChecked.compareTo(b.isChecked)
                if (byChecked != 0) byChecked else collator.compare(a.productName, b.productName)
            }
        }

    override suspend fun addItem(productId: String, amount: Int) {
        dao.addOrMerge(
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

    override suspend fun finishShopping() {
        dao.checkout(System.currentTimeMillis())
    }
}
