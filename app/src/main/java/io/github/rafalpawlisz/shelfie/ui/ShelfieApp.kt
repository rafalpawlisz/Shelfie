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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import io.github.rafalpawlisz.shelfie.ui.pantry.LowStockSuggestion
import io.github.rafalpawlisz.shelfie.ui.pantry.PantryViewModel
import io.github.rafalpawlisz.shelfie.ui.pantry.ProductFormDialog
import io.github.rafalpawlisz.shelfie.ui.pantry.ProductsScreen
import io.github.rafalpawlisz.shelfie.ui.pantry.RestockDialog
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
    // Ephemeral restock hint being acted on; deliberately not saved across
    // rotation — it's a suggestion, not state.
    var restockSuggestion by remember { mutableStateOf<LowStockSuggestion?>(null) }

    // Shows the low-stock snackbar; the Add action is offered only when there
    // is a list to add to. On action, opens the restock dialog.
    suspend fun suggestRestock(message: String, suggestion: LowStockSuggestion) {
        val canAdd = viewModel.uiState.value.lists.isNotEmpty()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = if (canAdd) context.getString(R.string.action_add) else null,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) restockSuggestion = suggestion
    }

    LaunchedEffect(Unit) {
        viewModel.scanEvents.collect { result ->
            when (result) {
                is UseUpScanResult.Used -> {
                    val suggestion = result.suggestion
                    if (suggestion != null) {
                        // ONE snackbar carrying both the outcome and the hint.
                        suggestRestock(
                            context.getString(R.string.use_up_scanned_low, result.productName),
                            suggestion,
                        )
                    } else {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.use_up_scanned, result.productName),
                        )
                    }
                }
                is UseUpScanResult.OutOfStock -> snackbarHostState.showSnackbar(
                    context.getString(R.string.use_up_scan_out_of_stock, result.productName),
                )
                is UseUpScanResult.UnknownCode -> snackbarHostState.showSnackbar(
                    context.getString(R.string.use_up_scan_unknown, result.code),
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        // Tap path (Use up list) — scans fold the hint into the Used event above.
        viewModel.lowStockEvents.collect { suggestion ->
            suggestRestock(
                context.getString(R.string.low_stock_message, suggestion.productName),
                suggestion,
            )
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
                        archivedLists = state.archivedLists,
                        selectedListId = state.selectedListId,
                        items = state.shoppingList,
                        onSelectList = viewModel::selectList,
                        onCreateList = viewModel::createList,
                        onRenameList = viewModel::renameList,
                        onArchiveList = viewModel::archiveList,
                        onRestoreList = viewModel::restoreList,
                        onDeleteList = viewModel::deleteList,
                        onMoveList = viewModel::moveList,
                        onToggle = viewModel::setShoppingItemChecked,
                        onRemove = viewModel::removeShoppingItem,
                        onSetAmount = viewModel::setShoppingItemAmount,
                        onCheckWithAmount = viewModel::checkWithAmount,
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

    val suggestion = restockSuggestion
    if (suggestion != null) {
        val product = state.products.firstOrNull { it.id == suggestion.productId }
        RestockDialog(
            productLabel = product
                ?.let { listOfNotNull(it.emoji, it.name).joinToString(" ") }
                ?: suggestion.productName,
            lists = state.lists,
            defaultListId = viewModel.defaultRestockListId(),
            onConfirm = { listId, amount ->
                viewModel.addToList(listId, suggestion.productId, amount)
                restockSuggestion = null
            },
            onDismiss = { restockSuggestion = null },
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
