package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.model.ItemSlot
import io.github.rafalpawlisz.shelfie.model.OneOffSuggestion
import io.github.rafalpawlisz.shelfie.model.PlannedEntry
import io.github.rafalpawlisz.shelfie.model.ProductCategory
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import kotlinx.coroutines.flow.Flow

interface ShoppingListRepository {

    // Lists
    fun observeLists(): Flow<List<ShoppingList>>
    fun observeArchivedLists(): Flow<List<ShoppingList>>
    suspend fun createList(name: String): String
    suspend fun renameList(id: String, name: String)

    /** The aisle order this shop is walked in; the default order stores nothing. */
    suspend fun setSectionOrder(listId: String, order: List<ProductCategory>)
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

    /**
     * A one-off item: a line of text on the list, bought once, never part of
     * the pantry. It has no stock to bank at checkout — "Finish shopping"
     * simply removes it — and repeats of the same name are allowed.
     *
     * [unit] is what the amount counts ("g", "opakowania"): a one-off has no
     * product to inherit a unit from, so it carries its own.
     */
    suspend fun addOneOffItem(
        listId: String,
        name: String,
        amount: Int?,
        unit: String? = null,
        note: String? = null,
    )

    /**
     * Names bought once before, newest first, so the picker can offer them
     * instead of asking for the same word again. Written by [addOneOffItem];
     * unlike the lines themselves it survives checkout.
     */
    fun observeOneOffSuggestions(): Flow<List<OneOffSuggestion>>

    /** Drops a remembered name — a typo, or something never to be bought again. */
    suspend fun forgetOneOffSuggestion(name: String)

    // True when the product already sits on any active (non-archived) list —
    // used to keep the low-stock suggestion from nagging about planned items.
    suspend fun isOnAnyList(productId: String): Boolean

    // Reactive planning map (list × product) powering the derived "low stock"
    // list and the move-between-lists guard.
    fun observePlannedEntries(): Flow<List<PlannedEntry>>

    /** Products on any list, archived lists included; gates permanent deletion. */
    fun observeReferencedProductIds(): Flow<List<String>>
    suspend fun setChecked(id: String, checked: Boolean)
    suspend fun setItemAmount(id: String, amount: Int?)
    // [unit] only lands on a one-off; a product row keeps its product's unit
    // whatever is passed here.
    suspend fun setItemDetails(id: String, amount: Int?, unit: String?, note: String?)

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

    /**
     * Manual reorder: persist the slots of one aisle, in one transaction.
     *
     * A whole aisle rather than the moved row alone, because its rows keep their
     * slots in two different places (see [ItemSlot]) and a single row's new
     * number is only meaningful next to the others'. Products' slots live on and
     * are found again when the product is re-added.
     */
    suspend fun setItemPositions(listId: String, slots: List<ItemSlot>)
}
