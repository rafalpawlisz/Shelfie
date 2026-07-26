package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.model.PlannedEntry
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import kotlinx.coroutines.flow.Flow

interface ShoppingListRepository {

    // Lists
    fun observeLists(): Flow<List<ShoppingList>>
    fun observeArchivedLists(): Flow<List<ShoppingList>>
    suspend fun createList(name: String): String
    suspend fun renameList(id: String, name: String)
    suspend fun archiveList(id: String)
    suspend fun restoreList(id: String)
    suspend fun deleteList(id: String)
    suspend fun setListPosition(id: String, position: Double)

    // Items within a given list
    fun observeItems(listId: String): Flow<List<ShoppingListItem>>

    // amount = null records the bare need ("just buy it"); the actual amount is
    // asked for when the item is checked off. note is a one-off shopping note
    // that dies with the item at checkout/removal.
    suspend fun addItem(listId: String, productId: String, amount: Int?, note: String? = null)

    // True when the product already sits on any active (non-archived) list —
    // used to keep the low-stock suggestion from nagging about planned items.
    suspend fun isOnAnyList(productId: String): Boolean

    // Reactive planning map (list × product) powering the derived "low stock"
    // list and the move-between-lists guard.
    fun observePlannedEntries(): Flow<List<PlannedEntry>>
    suspend fun setChecked(id: String, checked: Boolean)
    suspend fun setItemAmount(id: String, amount: Int?)
    suspend fun setItemDetails(id: String, amount: Int?, note: String?)

    // Move the item (amount + note travel along) to another list; arrives
    // unchecked, in its remembered slot there. No-op for the same list.
    suspend fun moveItem(id: String, targetListId: String)
    suspend fun removeItem(id: String)
    suspend fun finishShopping(listId: String)

    /**
     * Whether the list is still in the database (archived counts — it keeps its
     * items). Guards writes that carry a list id captured earlier, such as
     * undoing a removal seconds after the list itself was deleted.
     */
    suspend fun listExists(id: String): Boolean

    // Manual reorder: persist a product's sort position within the list. The
    // position lives in its own table, so it survives the item being removed.
    suspend fun setItemPosition(listId: String, productId: String, position: Double)
}
