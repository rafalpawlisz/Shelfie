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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.ui.pantry.AddShoppingItemDialog
import io.github.rafalpawlisz.shelfie.ui.pantry.PantryViewModel
import io.github.rafalpawlisz.shelfie.ui.pantry.ProductFormDialog
import io.github.rafalpawlisz.shelfie.ui.pantry.ProductsScreen
import io.github.rafalpawlisz.shelfie.ui.pantry.ShoppingScreen
import io.github.rafalpawlisz.shelfie.ui.pantry.UseUpScanResult
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
    var showAddToListDialog by rememberSaveable { mutableStateOf(false) }
    var editedProductId by rememberSaveable { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.scanEvents.collect { result ->
            val message = when (result) {
                is UseUpScanResult.Used ->
                    context.getString(R.string.use_up_scanned, result.productName)
                is UseUpScanResult.OutOfStock ->
                    context.getString(R.string.use_up_scan_out_of_stock, result.productName)
                is UseUpScanResult.UnknownCode ->
                    context.getString(R.string.use_up_scan_unknown, result.code)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            when (currentTab) {
                ShelfieTab.PRODUCTS -> FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_product),
                    )
                }
                ShelfieTab.SHOPPING ->
                    if (state.selectedListId != null) {
                        FloatingActionButton(onClick = { showAddToListDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.add_to_shopping_list),
                            )
                        }
                    }
                ShelfieTab.USE_UP ->
                    FloatingActionButton(
                        onClick = { scanBarcode(context) { viewModel.useUpByBarcode(it) } },
                    ) {
                        Icon(
                            imageVector = BarcodeIcon,
                            contentDescription = stringResource(R.string.scan_barcode),
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
                        archivedProducts = state.archivedProducts,
                        onProductClick = { editedProductId = it },
                    )
                    ShelfieTab.SHOPPING -> ShoppingScreen(
                        lists = state.lists,
                        selectedListId = state.selectedListId,
                        items = state.shoppingList,
                        onSelectList = viewModel::selectList,
                        onCreateList = viewModel::createList,
                        onRenameList = viewModel::renameList,
                        onDeleteList = viewModel::deleteList,
                        onToggle = viewModel::setShoppingItemChecked,
                        onRemove = viewModel::removeShoppingItem,
                        onMove = viewModel::moveShoppingItem,
                        onFinishShopping = viewModel::finishShopping,
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
            onConfirm = { name, quantity, unit, minQuantity, notes, emoji, barcodes ->
                viewModel.addProduct(name, quantity, unit, minQuantity, notes, emoji, barcodes)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    if (showAddToListDialog) {
        AddShoppingItemDialog(
            products = state.products,
            onConfirm = { productId, amount ->
                viewModel.addToShoppingList(productId, amount)
                showAddToListDialog = false
            },
            onDismiss = { showAddToListDialog = false },
        )
    }

    val editedActive = state.products.firstOrNull { it.id == editedProductId }
    val editedArchived =
        if (editedActive == null) {
            state.archivedProducts.firstOrNull { it.id == editedProductId }
        } else {
            null
        }
    val editedProduct = editedActive ?: editedArchived
    if (editedProduct != null) {
        ProductFormDialog(
            title = stringResource(R.string.edit_product),
            confirmLabel = stringResource(R.string.action_save),
            initialName = editedProduct.name,
            initialQuantity = editedProduct.quantity,
            initialUnit = editedProduct.unit,
            initialMinQuantity = editedProduct.minQuantity,
            initialNotes = editedProduct.notes,
            initialEmoji = editedProduct.emoji,
            initialBarcodes = state.barcodesByProduct[editedProduct.id].orEmpty(),
            stateKey = editedProduct.id,
            onConfirm = { name, quantity, unit, minQuantity, notes, emoji, barcodes ->
                viewModel.updateProduct(
                    editedProduct.id, name, quantity, unit, minQuantity, notes, emoji, barcodes,
                )
                editedProductId = null
            },
            onDismiss = { editedProductId = null },
            onArchive = if (editedActive != null) {
                {
                    viewModel.archive(editedProduct.id)
                    editedProductId = null
                }
            } else {
                null
            },
            onRestore = if (editedArchived != null) {
                {
                    viewModel.restore(editedProduct.id)
                    editedProductId = null
                }
            } else {
                null
            },
        )
    }
}
