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
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PantryUiState(
    val products: List<Product> = emptyList(),
    val archivedProducts: List<Product> = emptyList(),
    val shoppingList: List<ShoppingListItem> = emptyList(),
    val barcodesByProduct: Map<String, List<String>> = emptyMap(),
    val isLoading: Boolean = true,
)

class PantryViewModel(
    private val repository: ProductRepository,
    private val shoppingListRepository: ShoppingListRepository,
    private val barcodeRepository: BarcodeRepository,
) : ViewModel() {

    val uiState: StateFlow<PantryUiState> =
        combine(
            repository.observeProducts(),
            repository.observeArchivedProducts(),
            shoppingListRepository.observeItems(),
            barcodeRepository.observeBarcodes(),
        ) { active, archived, shopping, barcodes ->
            PantryUiState(
                products = active,
                archivedProducts = archived,
                shoppingList = shopping,
                barcodesByProduct = barcodes.groupBy({ it.productId }, { it.barcode }),
                isLoading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PantryUiState(isLoading = true),
        )

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

    fun addToShoppingList(productId: String, amount: Int) {
        viewModelScope.launch { shoppingListRepository.addItem(productId, amount) }
    }

    fun setShoppingItemChecked(id: String, checked: Boolean) {
        viewModelScope.launch { shoppingListRepository.setChecked(id, checked) }
    }

    fun removeShoppingItem(id: String) {
        viewModelScope.launch { shoppingListRepository.removeItem(id) }
    }

    fun finishShopping() {
        viewModelScope.launch { shoppingListRepository.finishShopping() }
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
