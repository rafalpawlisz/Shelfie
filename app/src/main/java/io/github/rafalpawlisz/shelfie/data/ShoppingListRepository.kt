package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import kotlinx.coroutines.flow.Flow

interface ShoppingListRepository {

    fun observeItems(): Flow<List<ShoppingListItem>>

    suspend fun addItem(productId: String, amount: Int)

    suspend fun setChecked(id: String, checked: Boolean)

    suspend fun removeItem(id: String)

    suspend fun finishShopping()
}
