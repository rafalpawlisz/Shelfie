package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.rafalpawlisz.shelfie.ShelfieApplication
import io.github.rafalpawlisz.shelfie.data.ProductRepository
import io.github.rafalpawlisz.shelfie.model.Product
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PantryUiState(
    val products: List<Product> = emptyList(),
    val isLoading: Boolean = true,
)

class PantryViewModel(private val repository: ProductRepository) : ViewModel() {

    val uiState: StateFlow<PantryUiState> = repository.observeProducts()
        .map { products -> PantryUiState(products = products, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PantryUiState(isLoading = true),
        )

    fun addProduct(name: String, quantity: Int, unit: String?) {
        viewModelScope.launch { repository.addProduct(name, quantity, unit) }
    }

    fun updateProduct(id: String, name: String, quantity: Int, unit: String?) {
        viewModelScope.launch { repository.updateProduct(id, name, quantity, unit) }
    }

    fun increment(id: String) {
        viewModelScope.launch { repository.adjustQuantity(id, delta = 1) }
    }

    fun decrement(id: String) {
        viewModelScope.launch { repository.adjustQuantity(id, delta = -1) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteProduct(id) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as ShelfieApplication
                PantryViewModel(app.container.productRepository)
            }
        }
    }
}
