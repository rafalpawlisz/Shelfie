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

/** One-shot outcome of scanning a barcode on the Use up tab. */
sealed interface UseUpScanResult {
    data class Used(val productName: String) : UseUpScanResult
    data class OutOfStock(val productName: String) : UseUpScanResult
    data class UnknownCode(val code: String) : UseUpScanResult
}

data class PantryUiState(
    val products: List<Product> = emptyList(),
    val archivedProducts: List<Product> = emptyList(),
    val lists: List<ShoppingList> = emptyList(),
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

    val uiState: StateFlow<PantryUiState> =
        combine(
            combine(
                repository.observeProducts(),
                repository.observeArchivedProducts(),
                barcodeRepository.observeBarcodes(),
            ) { active, archived, barcodes -> Triple(active, archived, barcodes) },
            shoppingListRepository.observeLists(),
            selectedListId,
            shoppingItems,
        ) { (active, archived, barcodes), lists, selected, items ->
            PantryUiState(
                products = active,
                archivedProducts = archived,
                lists = lists,
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
        viewModelScope.launch { repository.adjustQuantity(id, delta = -1) }
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
                product.quantity > 0 -> {
                    repository.adjustQuantity(product.id, delta = -1)
                    UseUpScanResult.Used(product.name)
                }
                else -> UseUpScanResult.OutOfStock(product.name)
            }
            scanChannel.send(result)
        }
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

    fun deleteList(id: String) {
        // The init reconciler reselects the first remaining list (or null).
        viewModelScope.launch { shoppingListRepository.deleteList(id) }
    }

    fun addToShoppingList(productId: String, amount: Int) {
        val listId = selectedListId.value ?: return
        viewModelScope.launch { shoppingListRepository.addItem(listId, productId, amount) }
    }

    fun setShoppingItemChecked(id: String, checked: Boolean) {
        viewModelScope.launch { shoppingListRepository.setChecked(id, checked) }
    }

    fun removeShoppingItem(id: String) {
        viewModelScope.launch { shoppingListRepository.removeItem(id) }
    }

    fun finishShopping() {
        val listId = selectedListId.value ?: return
        viewModelScope.launch { shoppingListRepository.finishShopping(listId) }
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
                )
            }
        }
    }
}
