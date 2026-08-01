package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.ShelfieApplication
import io.github.rafalpawlisz.shelfie.data.BarcodeRepository
import io.github.rafalpawlisz.shelfie.data.ProductRepository
import io.github.rafalpawlisz.shelfie.data.ShoppingListRepository
import io.github.rafalpawlisz.shelfie.data.UiPreferences
import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.model.ProductCategory
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A restock hint after a use-up left the stock below the product's minimum:
 * offer to put the product on a shopping list. [suggestedAmount] tops the
 * stock back up to the minimum.
 */
data class LowStockSuggestion(
    val productId: String,
    val productName: String,
    val suggestedAmount: Int,
)

/** One-shot outcome of a use-up (tap or scan) on the Use up tab. */
sealed interface UseUpScanResult {
    // A use-up may carry a restock suggestion so the UI can show ONE snackbar
    // with both messages; [productId] lets the plain variant offer Undo.
    data class Used(
        val productId: String,
        val productName: String,
        val suggestion: LowStockSuggestion? = null,
    ) : UseUpScanResult

    data class OutOfStock(val productName: String) : UseUpScanResult
    data class UnknownCode(val code: String) : UseUpScanResult
}

/**
 * Snapshot of a shopping-list item that was just removed, carrying everything
 * Undo needs to put it back ([ShoppingListItem] itself has no listId — the
 * removal always happens on the then-selected list).
 */
data class RemovedShoppingItem(
    val listId: String,
    // null = a one-off item; undo re-adds it by its name.
    val productId: String?,
    val productName: String,
    val amount: Int?,
    val note: String?,
)

data class PantryUiState(
    val products: List<Product> = emptyList(),
    val archivedProducts: List<Product> = emptyList(),
    // Derived "low stock" list: active products below their minimum that are not
    // yet planned on any active shopping list.
    val lowStockProducts: List<Product> = emptyList(),
    // productId -> ids of the active lists that already plan it; guards moves.
    val plannedByProduct: Map<String, Set<String>> = emptyMap(),
    // Products referenced by any list, archived lists included. An archived
    // product outside this set is the only kind that may be deleted for good.
    val referencedProductIds: Set<String> = emptySet(),
    val lists: List<ShoppingList> = emptyList(),
    val archivedLists: List<ShoppingList> = emptyList(),
    val selectedListId: String? = null,
    val shoppingList: List<ShoppingListItem> = emptyList(),
    val barcodesByProduct: Map<String, List<String>> = emptyMap(),
    val isLoading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PantryViewModel(
    private val repository: ProductRepository,
    private val shoppingListRepository: ShoppingListRepository,
    private val barcodeRepository: BarcodeRepository,
    private val uiPreferences: UiPreferences,
) : ViewModel() {

    // Which list the Shopping tab shows. Kept valid by the init reconciler
    // below; null only when there are no lists.
    private val selectedListId = MutableStateFlow<String?>(null)

    private val shoppingItems =
        selectedListId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else shoppingListRepository.observeItems(id)
        }

    // One-shot feedback for use-ups (tap or scan) on the Use up tab.
    private val useUpChannel = Channel<UseUpScanResult>(Channel.BUFFERED)
    val useUpEvents = useUpChannel.receiveAsFlow()

    // One-shot "item removed" events so the UI can offer Undo.
    private val itemRemovedChannel = Channel<RemovedShoppingItem>(Channel.BUFFERED)
    val itemRemovedEvents = itemRemovedChannel.receiveAsFlow()

    // One-shot plain messages (string resource ids) for things the user should
    // hear about but cannot act on.
    private val messageChannel = Channel<Int>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private data class ProductsBundle(
        val active: List<Product>,
        val archived: List<Product>,
        val barcodes: List<io.github.rafalpawlisz.shelfie.model.ProductBarcode>,
        val planned: List<io.github.rafalpawlisz.shelfie.model.PlannedEntry>,
        val referenced: List<String>,
    )

    val uiState: StateFlow<PantryUiState> =
        combine(
            combine(
                repository.observeProducts(),
                repository.observeArchivedProducts(),
                barcodeRepository.observeBarcodes(),
                shoppingListRepository.observePlannedEntries(),
                shoppingListRepository.observeReferencedProductIds(),
            ) { active, archived, barcodes, planned, referenced ->
                ProductsBundle(active, archived, barcodes, planned, referenced)
            },
            shoppingListRepository.observeLists(),
            shoppingListRepository.observeArchivedLists(),
            selectedListId,
            shoppingItems,
        ) { (active, archived, barcodes, planned, referenced), lists, archivedLists, selected, items ->
            val plannedByProduct = planned
                .groupBy({ it.productId }, { it.listId })
                .mapValues { (_, listIds) -> listIds.toSet() }
            PantryUiState(
                products = active,
                archivedProducts = archived,
                lowStockProducts = active.filter { product ->
                    val min = product.minQuantity
                    min != null && product.quantity < min && product.id !in plannedByProduct
                },
                plannedByProduct = plannedByProduct,
                referencedProductIds = referenced.toSet(),
                lists = lists,
                archivedLists = archivedLists,
                selectedListId = selected,
                shoppingList = items,
                barcodesByProduct = barcodes.groupBy({ it.productId }, { it.barcode }),
                isLoading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PantryUiState(isLoading = true),
        )

    init {
        // Keep the selection valid as lists appear/disappear.
        viewModelScope.launch {
            shoppingListRepository.observeLists().collect { lists ->
                val current = selectedListId.value
                selectedListId.value = when {
                    lists.isEmpty() -> null
                    lists.any { it.id == current } -> current
                    else -> lists.first().id
                }
            }
        }
    }

    fun addProduct(
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int? = null,
        notes: String? = null,
        emoji: String? = null,
        expiresOn: String? = null,
        barcodes: List<String> = emptyList(),
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            resolveProduct(trimmed, quantity, unit, minQuantity, notes, emoji, expiresOn, barcodes)
        }
    }

    fun updateProduct(
        id: String,
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int? = null,
        notes: String? = null,
        emoji: String? = null,
        expiresOn: String? = null,
        addedBarcodes: List<String> = emptyList(),
        removedBarcodes: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            repository.updateProduct(
                id, name, quantity, unit, minQuantity, notes, emoji, expiresOn,
            )
            // The form reports what the user changed, so a barcode that the
            // household added while the form was open is left alone instead of
            // looking like a removal. Removals are scoped to this product.
            addedBarcodes.forEach { barcodeRepository.addBarcode(id, it) }
            removedBarcodes.forEach { barcodeRepository.removeBarcode(id, it) }
        }
    }

    fun decrement(id: String) {
        viewModelScope.launch {
            val product = repository.getActiveProduct(id) ?: return@launch
            if (product.quantity <= 0) return@launch
            useUpChannel.send(
                UseUpScanResult.Used(product.id, product.name, useUpProduct(product)),
            )
        }
    }

    fun useUpByBarcode(code: String) {
        viewModelScope.launch {
            val productId = barcodeRepository.findProductId(code)
            // Fetch straight from the repository, not uiState: returning from the
            // scanner's own activity can leave the WhileSubscribed uiState stale.
            // Only active products can be used up; archived/unknown → UnknownCode.
            val product = productId?.let { repository.getActiveProduct(it) }
            val result = when {
                product == null -> UseUpScanResult.UnknownCode(code)
                product.quantity > 0 ->
                    UseUpScanResult.Used(product.id, product.name, useUpProduct(product))
                else -> UseUpScanResult.OutOfStock(product.name)
            }
            useUpChannel.send(result)
        }
    }

    /** Undo of the last use-up snackbar: put the unit back. */
    fun undoUseUp(productId: String) {
        viewModelScope.launch { repository.adjustQuantity(productId, delta = +1) }
    }

    /**
     * Consume one unit and return a restock suggestion when the stock lands
     * below the product's minimum. Fires on every such use-up (the amount grows
     * as the stock shrinks); the "already on a list" check is what keeps it from
     * nagging. Whether the UI offers an Add action (there may be no lists) is
     * the UI's call.
     */
    private suspend fun useUpProduct(product: Product): LowStockSuggestion? {
        repository.adjustQuantity(product.id, delta = -1)
        val min = product.minQuantity ?: return null
        val newQuantity = product.quantity - 1
        if (newQuantity >= min) return null
        if (shoppingListRepository.isOnAnyList(product.id)) return null
        return LowStockSuggestion(
            productId = product.id,
            productName = product.name,
            suggestedAmount = maxOf(1, min - newQuantity),
        )
    }

    fun selectList(id: String) {
        selectedListId.value = id
    }

    fun createList(name: String) {
        viewModelScope.launch {
            // Newly created list becomes the selected one.
            selectedListId.value = shoppingListRepository.createList(name)
        }
    }

    fun renameList(id: String, name: String) {
        viewModelScope.launch { shoppingListRepository.renameList(id, name) }
    }

    /**
     * Persist a manual reorder of the lists: move the list at [fromIndex] to
     * [toIndex]. Only the moved list's position changes — it's set to the midpoint
     * between its new neighbours (fractional indexing).
     */
    fun moveList(fromIndex: Int, toIndex: Int) {
        val lists = uiState.value.lists
        if (fromIndex !in lists.indices || toIndex !in lists.indices || fromIndex == toIndex) return
        val moved = lists[fromIndex]
        val without = lists.toMutableList().apply { removeAt(fromIndex) }
        val prev = without.getOrNull(toIndex - 1)?.position
        val next = without.getOrNull(toIndex)?.position
        val newPosition = when {
            prev == null && next == null -> moved.position
            prev == null -> next!! - 1.0
            next == null -> prev + 1.0
            else -> (prev + next) / 2.0
        }
        viewModelScope.launch {
            shoppingListRepository.setListPosition(moved.id, newPosition)
        }
    }

    fun archiveList(id: String) {
        // Reversible soft delete; the reconciler reselects another active list
        // (or null). Items and manual order stay put for a later restore.
        viewModelScope.launch { shoppingListRepository.archiveList(id) }
    }

    fun restoreList(id: String) {
        viewModelScope.launch { shoppingListRepository.restoreList(id) }
    }

    fun deleteList(id: String) {
        // Permanent (only reachable from the archive view); items cascade.
        viewModelScope.launch { shoppingListRepository.deleteList(id) }
    }

    fun addToShoppingList(productId: String, amount: Int?, note: String? = null) {
        val listId = selectedListId.value ?: return
        viewModelScope.launch { planOnList(listId, productId, amount, note) }
    }

    /** Add to an explicitly chosen list (restock dialog) and remember the choice. */
    fun addToList(listId: String, productId: String, amount: Int?) {
        uiPreferences.lastRestockListId = listId
        viewModelScope.launch { planOnList(listId, productId, amount, note = null) }
    }

    /**
     * Put a product on a list, bringing it back from the archive if that is
     * where it was.
     *
     * The restore is not a courtesy: observeItems joins products with
     * `archivedAt IS NULL`, so an item pointing at an archived product is
     * invisible — planning one would add a row nobody can see. Wanting to buy
     * something is also the plainest possible statement that it belongs in the
     * pantry again.
     */
    private suspend fun planOnList(listId: String, productId: String, amount: Int?, note: String?) {
        if (repository.getActiveProduct(productId) == null) {
            repository.restoreProduct(productId)
        }
        shoppingListRepository.addItem(listId, productId, amount, note)
    }

    /**
     * The product just created from the shopping-list picker, so the picker can
     * carry on with it. Null once the UI has taken it, which
     * [clearProductForList] says explicitly rather than leaving a stale id to
     * reopen the dialog on the next recomposition.
     */
    private val _productForList = MutableStateFlow<String?>(null)
    val productForList: StateFlow<String?> = _productForList

    fun clearProductForList() {
        _productForList.value = null
    }

    /**
     * Create a product from inside the list picker, through the same full form
     * the Products tab uses — one meaning for "add a product", not two.
     * Publishes the id through [productForList] so the picker can continue to
     * the amount step with it.
     */
    fun addProductForList(
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int? = null,
        notes: String? = null,
        emoji: String? = null,
        expiresOn: String? = null,
        barcodes: List<String> = emptyList(),
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _productForList.value = resolveProduct(
                trimmed, quantity, unit, minQuantity, notes, emoji, expiresOn, barcodes,
            )
        }
    }

    /**
     * Reach the product of that name, creating one only when the pantry has
     * none: two "Mleko" split the barcode a scan finds, the low-stock list and
     * what the other phone sees. Both forms come through here.
     *
     * An existing product's stored details are LEFT ALONE. The forms warn about
     * a taken name while it is typed, so this is the race guard — the other
     * device created or archived that name while the form was open — and in a
     * race the established product is worth more than a form the user filled
     * believing it was blank. Overwriting here once emptied an archived
     * product's unit, minimum and notes, because that form is blank by design.
     * A product coming back from the archive says so, rather than leaving the
     * restore to be discovered.
     *
     * Barcodes are the exception, being additive: a code scanned into the form
     * is new information about the product, not a replacement for it.
     */
    private suspend fun resolveProduct(
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int?,
        notes: String?,
        emoji: String?,
        expiresOn: String?,
        barcodes: List<String>,
    ): String {
        val existing = repository.findByName(name)
        val id = if (existing == null) {
            repository.addProduct(name, quantity, unit, minQuantity, notes, emoji, expiresOn)
        } else {
            // Whether it is archived is asked of the database rather than of
            // uiState, which reports nothing when the screen is not collecting.
            if (repository.getActiveProduct(existing.id) == null) {
                repository.restoreProduct(existing.id)
                messageChannel.send(R.string.product_back_from_archive)
            }
            existing.id
        }
        barcodes.forEach { barcodeRepository.addBarcode(id, it) }
        return id
    }

    /**
     * "Add all" on the derived low-stock list: put every below-minimum product
     * on the chosen list without an amount (it's asked for at check-off).
     */
    fun addLowStockToList(listId: String) {
        val products = uiState.value.lowStockProducts
        if (products.isEmpty()) return
        uiPreferences.lastRestockListId = listId
        viewModelScope.launch {
            products.forEach { shoppingListRepository.addItem(listId, it.id, amount = null) }
        }
    }

    /** Which list the restock dialog should preselect: last used, else selected, else first. */
    fun defaultRestockListId(): String? {
        val lists = uiState.value.lists
        val remembered = uiPreferences.lastRestockListId
        return when {
            lists.any { it.id == remembered } -> remembered
            selectedListId.value != null -> selectedListId.value
            else -> lists.firstOrNull()?.id
        }
    }

    fun setShoppingItemChecked(id: String, checked: Boolean) {
        viewModelScope.launch { shoppingListRepository.setChecked(id, checked) }
    }

    /**
     * Row-tap edit: amount and the one-off note saved together; a different
     * [targetListId] additionally moves the item to that list.
     */
    fun updateShoppingItem(id: String, amount: Int?, note: String?, targetListId: String? = null) {
        if (amount != null && amount <= 0) return
        viewModelScope.launch {
            shoppingListRepository.setItemDetails(id, amount, note)
            if (targetListId != null) shoppingListRepository.moveItem(id, targetListId)
        }
    }

    /**
     * Check off an item that had no amount: the check-off dialog just asked how
     * many were bought. One coroutine so the amount lands before the check.
     */
    fun checkWithAmount(id: String, amount: Int) {
        if (amount <= 0) return
        viewModelScope.launch {
            shoppingListRepository.setItemAmount(id, amount)
            shoppingListRepository.setChecked(id, checked = true)
        }
    }

    fun removeShoppingItem(id: String) {
        // Snapshot before deleting so the snackbar can offer Undo.
        val item = uiState.value.shoppingList.firstOrNull { it.id == id }
        val listId = selectedListId.value
        viewModelScope.launch {
            shoppingListRepository.removeItem(id)
            if (item != null && listId != null) {
                itemRemovedChannel.send(
                    RemovedShoppingItem(
                        listId = listId,
                        productId = item.productId,
                        productName = item.productName,
                        amount = item.amount,
                        note = item.note,
                    ),
                )
            }
        }
    }

    /**
     * Undo of a remove: put the item back on its list with the same amount and
     * note. The manual position survives removal (product_list_order is kept),
     * so the item returns to its old slot; it comes back unchecked.
     */
    fun undoRemoveItem(removed: RemovedShoppingItem) {
        viewModelScope.launch {
            // The snackbar outlives its list: ten seconds is enough to archive
            // the list (fine — the item returns to a dormant list) or delete it
            // for good, and inserting into a list that is gone trips the
            // foreign key and takes the app down.
            if (!shoppingListRepository.listExists(removed.listId)) {
                messageChannel.send(R.string.undo_list_gone)
                return@launch
            }
            if (removed.productId == null) {
                // A one-off comes back as a fresh line under the same name —
                // there is no product row for it to return to.
                shoppingListRepository.addOneOffItem(
                    removed.listId,
                    removed.productName,
                    removed.amount,
                    removed.note,
                )
            } else {
                shoppingListRepository.addItem(
                    removed.listId,
                    removed.productId,
                    removed.amount,
                    removed.note,
                )
            }
        }
    }

    fun finishShopping() {
        val listId = selectedListId.value ?: return
        viewModelScope.launch { shoppingListRepository.finishShopping(listId) }
    }

    /**
     * Persist a manual reorder: move the item at [fromIndex] to [toIndex] within
     * the current sorted shopping list. Only the moved item's position changes —
     * it's set to the midpoint between its new neighbours (fractional indexing),
     * so absent products keep their remembered slots.
     */
    fun moveShoppingItem(fromIndex: Int, toIndex: Int) {
        val listId = selectedListId.value ?: return
        val items = uiState.value.shoppingList
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) return
        val moved = items[fromIndex]
        // Only unchecked items carry a manual position; checked items are parked at
        // the bottom by check time and aren't repositioned by dragging. One-off
        // items have no product slot to remember a position for.
        if (moved.isChecked) return
        val movedProductId = moved.productId ?: return
        // A drag never crosses a section: the section is the product's, not the
        // row's, so dropping into another aisle could not stick — the sort
        // would put the row straight back. Landing there is a no-op instead.
        val movedSection = sectionOf(moved)
        val without = items.toMutableList().apply { removeAt(fromIndex) }
        // Neighbours must be unchecked, of the same section, and product-backed.
        // A row across either boundary keeps its own position range and must
        // not pull the dropped item into it — and a one-off's "position" is its
        // creation time in millis, so borrowing it would push a real product to
        // the end of its aisle forever (positions outlive the item).
        fun ShoppingListItem.usableNeighbour(): Boolean =
            !isChecked && sectionOf(this) == movedSection && productId != null
        val prevItem = without.getOrNull(toIndex - 1)?.takeIf { it.usableNeighbour() }
        val nextItem = without.getOrNull(toIndex)?.takeIf { it.usableNeighbour() }
        // Nothing of the same section on either side of the drop — the drag
        // left its aisle entirely; the resync snaps the row back.
        if (prevItem == null && nextItem == null) return
        val prev = prevItem?.position
        val next = nextItem?.position
        val newPosition = when {
            prev == null && next == null -> moved.position
            prev == null -> next!! - 1.0
            next == null -> prev + 1.0
            else -> (prev + next) / 2.0
        }
        viewModelScope.launch {
            shoppingListRepository.setItemPosition(listId, movedProductId, newPosition)
        }
    }

    // null covers one-offs, pre-section emoji and "no section" alike — they all
    // share the trailing sectionless group.
    private fun sectionOf(item: ShoppingListItem): ProductCategory? =
        ProductCategory.fromEmoji(item.productEmoji)

    /** A one-off onto the currently selected list; see [ShoppingListRepository.addOneOffItem]. */
    fun addOneOffToShoppingList(name: String, amount: Int?, note: String? = null) {
        val listId = selectedListId.value ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            shoppingListRepository.addOneOffItem(listId, name, amount, note)
        }
    }

    fun archive(id: String) {
        viewModelScope.launch { repository.archiveProduct(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restoreProduct(id) }
    }

    /**
     * Delete an archived product for good. The UI only offers this for a
     * product no list refers to, but the repository checks again and can
     * refuse: the other device may have put it on a list in the meantime, and
     * deleting it would take that item down with it.
     */
    fun deleteArchived(id: String) {
        viewModelScope.launch {
            if (!repository.deleteArchivedProduct(id)) {
                messageChannel.send(R.string.delete_product_in_use)
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as ShelfieApplication
                PantryViewModel(
                    app.container.productRepository,
                    app.container.shoppingListRepository,
                    app.container.barcodeRepository,
                    app.container.uiPreferences,
                )
            }
        }
    }
}
