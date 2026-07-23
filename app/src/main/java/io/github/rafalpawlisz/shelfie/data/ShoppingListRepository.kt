package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import kotlinx.coroutines.flow.Flow

interface ShoppingListRepository {

    // Lists
    fun observeLists(): Flow<List<ShoppingList>>
    suspend fun createList(name: String): String
    suspend fun renameList(id: String, name: String)
    suspend fun deleteList(id: String)

    // Items within a given list
    fun observeItems(listId: String): Flow<List<ShoppingListItem>>
    suspend fun addItem(listId: String, productId: String, amount: Int)
    suspend fun setChecked(id: String, checked: Boolean)
    suspend fun removeItem(id: String)
    suspend fun finishShopping(listId: String)
}
