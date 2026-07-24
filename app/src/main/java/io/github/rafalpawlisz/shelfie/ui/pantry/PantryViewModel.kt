package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.rafalpawlisz.shelfie.ShelfieApplication
import io.github.rafalpawlisz.shelfie.data.BarcodeRepository
import io.github.rafalpawlisz.shelfie.data.ProductRepository
import io.github.rafalpawlisz.shelfie.data.ShoppingListRepository
import io.github.rafalpawlisz.shelfie.data.UiPreferences
import io.github.rafalpawlisz.shelfie.model.Product
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

/** One-shot outcome of scanning a barcode on the Use up tab. */
sealed interface UseUpScanResult {
    // A successful scan may carry a restock suggestion so the UI can show ONE
    // snackbar with both messages instead of queueing two.
    data class Used(
        val productName: String,
        val suggestion: LowStockSuggestion? = null,
    ) : UseUpScanResult

    data class OutOfStock(val productName: String) : UseUpScanResult
    data class UnknownCode(val code: String) : UseUpScanResult
}

data class PantryUiState(
    val products: List<Product> = emptyList(),
    val archivedProducts: List<Product> = emptyList(),
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

    // One-shot feedback for barcode scans on the Use up tab.
    private val scanChannel = Channel<UseUpScanResult>(Channel.BUFFERED)
    val scanEvents = scanChannel.receiveAsFlow()

    // One-shot restock hints for the tap path (scans fold the hint into Used).
    private val lowStockChannel = Channel<LowStockSuggestion>(Channel.BUFFERED)
    val lowStockEvents = lowStockChannel.receiveAsFlow()

    val uiState: StateFlow<PantryUiState> =
        combine(
            combine(
                repository.observeProducts(),
                repository.observeArchivedProducts(),
                barcodeRepository.observeBarcodes(),
            ) { active, archived, barcodes -> Triple(active, archived, barcodes) },
            shoppingListRepository.observeLists(),
            shoppingListRepository.observeArchivedLists(),
            selectedListId,
            shoppingItems,
        ) { (active, archived, barcodes), lists, archivedLists, selected, items ->
            PantryUiState(
                products = active,
                archivedProducts = archived,
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
        barcodes: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            val id = repository.addProduct(name, quantity, unit, minQuantity, notes, emoji)
            barcodes.forEach { barcodeRepository.addBarcode(id, it) }
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
        barcodes: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            repository.updateProduct(id, name, quantity, unit, minQuantity, notes, emoji)
            val current = uiState.value.barcodesByProduct[id].orEmpty().toSet()
            val target = barcodes.toSet()
            (target - current).forEach { barcodeRepository.addBarcode(id, it) }
            (current - target).forEach { barcodeRepository.removeBarcode(it) }
        }
    }

    fun decrement(id: String) {
        viewModelScope.launch {
            val product = repository.getActiveProduct(id) ?: return@launch
            if (product.quantity <= 0) return@launch
            useUpProduct(product)?.let { lowStockChannel.send(it) }
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
                product.quantity > 0 -> UseUpScanResult.Used(product.name, useUpProduct(product))
                else -> UseUpScanResult.OutOfStock(product.name)
            }
            scanChannel.send(result)
        }
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
        viewModelScope.launch { shoppingListRepository.addItem(listId, productId, amount, note) }
    }

    /** Add to an explicitly chosen list (restock dialog) and remember the choice. */
    fun addToList(listId: String, productId: String, amount: Int?) {
        uiPreferences.lastRestockListId = listId
        viewModelScope.launch { shoppingListRepository.addItem(listId, productId, amount) }
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

    /** Row-tap edit: amount and the one-off note saved together. */
    fun updateShoppingItem(id: String, amount: Int?, note: String?) {
        if (amount != null && amount <= 0) return
        viewModelScope.launch { shoppingListRepository.setItemDetails(id, amount, note) }
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
        viewModelScope.launch { shoppingListRepository.removeItem(id) }
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
        // the bottom by check time and aren't repositioned by dragging.
        if (moved.isChecked) return
        val without = items.toMutableList().apply { removeAt(fromIndex) }
        // Neighbours must be unchecked — a checked item at the boundary keeps its
        // own retained position and must not pull the dropped item into its range.
        val prev = without.getOrNull(toIndex - 1)?.takeUnless { it.isChecked }?.position
        val next = without.getOrNull(toIndex)?.takeUnless { it.isChecked }?.position
        val newPosition = when {
            prev == null && next == null -> moved.position
            prev == null -> next!! - 1.0
            next == null -> prev + 1.0
            else -> (prev + next) / 2.0
        }
        viewModelScope.launch {
            shoppingListRepository.setItemPosition(listId, moved.productId, newPosition)
        }
    }

    fun archive(id: String) {
        viewModelScope.launch { repository.archiveProduct(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restoreProduct(id) }
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
