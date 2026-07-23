package io.github.rafalpawlisz.shelfie.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.ui.pantry.PantryViewModel
import io.github.rafalpawlisz.shelfie.ui.pantry.ProductFormDialog
import io.github.rafalpawlisz.shelfie.ui.pantry.ProductsScreen
import io.github.rafalpawlisz.shelfie.ui.pantry.ShoppingScreen
import io.github.rafalpawlisz.shelfie.ui.pantry.UseUpScreen

enum class ShelfieTab(@field:StringRes val labelRes: Int, val icon: ImageVector) {
    PRODUCTS(R.string.tab_products, Icons.AutoMirrored.Filled.List),
    SHOPPING(R.string.tab_shopping, Icons.Default.ShoppingCart),
    USE_UP(R.string.tab_use_up, RemoveIcon),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfieApp(viewModel: PantryViewModel = viewModel(factory = PantryViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var currentTab by rememberSaveable { mutableStateOf(ShelfieTab.PRODUCTS) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editedProductId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(currentTab.labelRes)) }) },
        bottomBar = {
            NavigationBar {
                ShelfieTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentTab == ShelfieTab.PRODUCTS) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_product),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (!state.isLoading) {
                when (currentTab) {
                    ShelfieTab.PRODUCTS -> ProductsScreen(
                        products = state.products,
                        onProductClick = { editedProductId = it },
                    )
                    ShelfieTab.SHOPPING -> ShoppingScreen(
                        products = state.products,
                        onIncrement = viewModel::increment,
                    )
                    ShelfieTab.USE_UP -> UseUpScreen(
                        products = state.products,
                        onDecrement = viewModel::decrement,
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        ProductFormDialog(
            title = stringResource(R.string.add_product),
            confirmLabel = stringResource(R.string.action_add),
            onConfirm = { name, quantity, unit ->
                viewModel.addProduct(name, quantity, unit)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    val editedProduct = state.products.firstOrNull { it.id == editedProductId }
    if (editedProduct != null) {
        ProductFormDialog(
            title = stringResource(R.string.edit_product),
            confirmLabel = stringResource(R.string.action_save),
            initialName = editedProduct.name,
            initialQuantity = editedProduct.quantity,
            initialUnit = editedProduct.unit,
            stateKey = editedProduct.id,
            onConfirm = { name, quantity, unit ->
                viewModel.updateProduct(editedProduct.id, name, quantity, unit)
                editedProductId = null
            },
            onDismiss = { editedProductId = null },
            onDelete = {
                viewModel.delete(editedProduct.id)
                editedProductId = null
            },
        )
    }
}
