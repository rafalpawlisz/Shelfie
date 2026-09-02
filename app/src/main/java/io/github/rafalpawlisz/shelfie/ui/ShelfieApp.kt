package io.github.rafalpawlisz.shelfie.ui

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rafalpawlisz.shelfie.R
import kotlinx.coroutines.flow.collectLatest
import io.github.rafalpawlisz.shelfie.ui.pantry.AddShoppingItemDialog
import io.github.rafalpawlisz.shelfie.ui.pantry.decorationFor
import io.github.rafalpawlisz.shelfie.ui.pantry.LowStockSuggestion
import io.github.rafalpawlisz.shelfie.ui.pantry.PantryViewModel
import io.github.rafalpawlisz.shelfie.ui.pantry.ProductFormDialog
import io.github.rafalpawlisz.shelfie.ui.pantry.ProductsScreen
import io.github.rafalpawlisz.shelfie.ui.pantry.productNameConflict
import io.github.rafalpawlisz.shelfie.ui.pantry.RestockDialog
import io.github.rafalpawlisz.shelfie.ui.pantry.ShoppingScreen
import io.github.rafalpawlisz.shelfie.ui.pantry.UseUpAmountDialog
import io.github.rafalpawlisz.shelfie.ui.pantry.UseUpScanResult
import io.github.rafalpawlisz.shelfie.ui.pantry.UseUpScreen
import io.github.rafalpawlisz.shelfie.ui.settings.AuthViewModel
import io.github.rafalpawlisz.shelfie.ui.settings.SettingsDialog

enum class ShelfieTab(@field:StringRes val labelRes: Int, val icon: ImageVector) {
    PRODUCTS(R.string.tab_products, Icons.AutoMirrored.Filled.List),
    SHOPPING(R.string.tab_shopping, Icons.Default.ShoppingCart),
    USE_UP(R.string.tab_use_up, RemoveIcon),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfieApp(
    viewModel: PantryViewModel = viewModel(factory = PantryViewModel.Factory),
    authViewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val household by authViewModel.household.collectAsStateWithLifecycle()
    val pendingUseUp by viewModel.pendingUseUp.collectAsStateWithLifecycle()
    var currentTab by rememberSaveable { mutableStateOf(ShelfieTab.PRODUCTS) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showAddToListDialog by rememberSaveable { mutableStateOf(false) }
    // The picker's detour through the product form: the name on the way there,
    // the created product's id on the way back.
    var newProductForListName by rememberSaveable { mutableStateOf<String?>(null) }
    var newProductForListId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var editedProductId by rememberSaveable { mutableStateOf<String?>(null) }

    // The Context is here for the scanner, which needs a real one. Strings are
    // read off Resources instead: a Context read is not invalidated when the
    // Configuration changes, so a message built from it could come out in the
    // language the screen was composed in rather than the one in force now.
    val context = LocalContext.current
    val resources = LocalResources.current
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
            actionLabel = if (canAdd) resources.getString(R.string.action_add) else null,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) restockSuggestion = suggestion
    }

    LaunchedEffect(Unit) {
        // collectLatest: a burst of use-ups must not queue snackbars — a new
        // event cancels the previous handler, which dismisses its snackbar
        // (showSnackbar cleans up on cancellation), so the newest wins.
        viewModel.useUpEvents.collectLatest { result ->
            when (result) {
                is UseUpScanResult.Used -> {
                    val suggestion = result.suggestion
                    if (suggestion != null) {
                        // ONE snackbar carrying both the outcome and the hint;
                        // the restock action outranks Undo (one action slot).
                        suggestRestock(
                            resources.getString(R.string.use_up_scanned_low, result.productName),
                            suggestion,
                        )
                    } else {
                        val shown = snackbarHostState.showSnackbar(
                            message = resources.getString(R.string.use_up_scanned, result.productName),
                            actionLabel = resources.getString(R.string.action_undo),
                            // With an action label M3 defaults to Indefinite —
                            // an undo hint must not linger forever.
                            duration = SnackbarDuration.Long,
                        )
                        if (shown == SnackbarResult.ActionPerformed) {
                            viewModel.undoUseUp(result.productId, result.amount)
                        }
                    }
                }
                is UseUpScanResult.OutOfStock -> snackbarHostState.showSnackbar(
                    resources.getString(R.string.use_up_scan_out_of_stock, result.productName),
                )
                is UseUpScanResult.UnknownCode -> snackbarHostState.showSnackbar(
                    resources.getString(R.string.use_up_scan_unknown, result.code),
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collectLatest { messageRes ->
            snackbarHostState.showSnackbar(resources.getString(messageRes))
        }
    }

    LaunchedEffect(Unit) {
        // Separate collector from use-up events: removals and use-ups come from
        // different tabs, and each stream should replace only its own snackbar.
        viewModel.itemRemovedEvents.collectLatest { removed ->
            val shown = snackbarHostState.showSnackbar(
                message = resources.getString(R.string.shopping_item_removed, removed.productName),
                actionLabel = resources.getString(R.string.action_undo),
                // With an action label M3 defaults to Indefinite.
                duration = SnackbarDuration.Long,
            )
            if (shown == SnackbarResult.ActionPerformed) {
                viewModel.undoRemoveItem(removed)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(currentTab.labelRes)) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
            )
        },
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
                Crossfade(targetState = currentTab, label = "tab") { tab ->
                    when (tab) {
                        ShelfieTab.PRODUCTS -> ProductsScreen(
                            products = state.products,
                            archivedProducts = state.archivedProducts,
                            onProductClick = { editedProductId = it },
                        )
                        ShelfieTab.SHOPPING -> ShoppingScreen(
                            lists = state.lists,
                            archivedLists = state.archivedLists,
                            emptyListIds = state.emptyListIds,
                            selectedListId = state.selectedListId,
                            items = state.shoppingList,
                            lowStockProducts = state.lowStockProducts,
                            plannedByProduct = state.plannedByProduct,
                            onRestockProduct = { product ->
                                // Reuse the restock dialog (store picker, remembered
                                // list, empty amount) for a tapped shortage.
                                restockSuggestion = LowStockSuggestion(
                                    productId = product.id,
                                    productName = product.name,
                                    suggestedAmount = maxOf(
                                        1,
                                        (product.minQuantity ?: 1) - product.quantity,
                                    ),
                                )
                            },
                            onAddAllLowStock = viewModel::addLowStockToList,
                            onSelectList = viewModel::selectList,
                            onCreateList = viewModel::createList,
                            onRenameList = viewModel::renameList,
                            onSetSectionOrder = viewModel::setSectionOrder,
                            onArchiveList = viewModel::archiveList,
                            onRestoreList = viewModel::restoreList,
                            onDeleteList = viewModel::deleteList,
                            onMoveList = viewModel::moveList,
                            onToggle = viewModel::setShoppingItemChecked,
                            onRemove = viewModel::removeShoppingItem,
                            onUpdateItem = viewModel::updateShoppingItem,
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
    }

    val pending = pendingUseUp
    if (pending != null) {
        UseUpAmountDialog(
            product = pending,
            onConfirm = viewModel::confirmUseUp,
            onDismiss = viewModel::cancelUseUp,
        )
    }

    if (showSettings) {
        val error by authViewModel.error.collectAsStateWithLifecycle()
        val pending by authViewModel.pending.collectAsStateWithLifecycle()
        val rememberedInviteCode by
            authViewModel.rememberedInviteCode.collectAsStateWithLifecycle()
        val syncStatus by authViewModel.syncStatus.collectAsStateWithLifecycle()
        SettingsDialog(
            household = household,
            syncStatus = syncStatus,
            hasLocalData = state.products.isNotEmpty() ||
                state.archivedProducts.isNotEmpty() ||
                state.lists.isNotEmpty() ||
                state.archivedLists.isNotEmpty(),
            rememberedInviteCode = rememberedInviteCode,
            error = error,
            pending = pending,
            onCreateHousehold = authViewModel::createHousehold,
            onJoinHousehold = authViewModel::joinHousehold,
            onRenameHousehold = authViewModel::renameHousehold,
            onLeaveHousehold = authViewModel::leaveHousehold,
            onDismiss = {
                showSettings = false
                authViewModel.clearError()
            },
        )
    }

    if (showAddDialog) {
        ProductFormDialog(
            title = stringResource(R.string.add_product),
            confirmLabel = stringResource(R.string.action_add),
            autoFocusName = true,
            nameConflictOf = { typed ->
                productNameConflict(state.products, state.archivedProducts, typed)
            },
            // A new product has nothing to remove from.
            onConfirm = { name, quantity, unit, minQuantity, notes, emoji, expiresOn, added, _ ->
                viewModel.addProduct(
                    name, quantity, unit, minQuantity, notes, emoji, expiresOn,
                    barcodes = added,
                )
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
                ?.let { listOfNotNull(decorationFor(it.name), it.name).joinToString(" ") }
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

    // A product created from the picker comes back through the ViewModel; the
    // picker reopens with it chosen, so the trip through the product form ends
    // where it started instead of dropping the user back on the list.
    val createdForList by viewModel.productForList.collectAsStateWithLifecycle()
    LaunchedEffect(createdForList) {
        val id = createdForList ?: return@LaunchedEffect
        newProductForListId = id
        showAddToListDialog = true
        viewModel.clearProductForList()
    }

    if (showAddToListDialog) {
        AddShoppingItemDialog(
            products = state.products,
            archivedProducts = state.archivedProducts,
            items = state.shoppingList,
            preselectProductId = newProductForListId,
            onConfirm = { productId, amount, note ->
                viewModel.addToShoppingList(productId, amount, note)
                showAddToListDialog = false
                newProductForListId = null
            },
            suggestions = state.oneOffSuggestions,
            onForgetSuggestion = viewModel::forgetOneOffSuggestion,
            onConfirmOneOff = { name, amount, unit, note, sectionEmoji ->
                viewModel.addOneOffToShoppingList(name, amount, unit, note, sectionEmoji)
                showAddToListDialog = false
                newProductForListId = null
            },
            // Creating goes through the same full form as the Products tab —
            // one meaning for "add a product". The picker steps aside for it.
            onCreateProduct = { name ->
                showAddToListDialog = false
                newProductForListName = name
            },
            onDismiss = {
                showAddToListDialog = false
                newProductForListId = null
            },
        )
    }

    val newForListName = newProductForListName
    if (newForListName != null) {
        ProductFormDialog(
            title = stringResource(R.string.add_product),
            confirmLabel = stringResource(R.string.action_add),
            initialName = newForListName,
            stateKey = newForListName,
            onConfirm = { name, quantity, unit, minQuantity, notes, emoji, expiresOn, added, _ ->
                viewModel.addProductForList(
                    name = name,
                    quantity = quantity,
                    unit = unit,
                    minQuantity = minQuantity,
                    notes = notes,
                    emoji = emoji,
                    expiresOn = expiresOn,
                    barcodes = added,
                )
                newProductForListName = null
            },
            onDismiss = {
                // Backing out of the form returns to the picker with the typed
                // name still there, rather than to the bare list.
                newProductForListName = null
                showAddToListDialog = true
            },
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
            initialExpiresOn = editedProduct.expiresOn,
            initialBarcodes = state.barcodesByProduct[editedProduct.id].orEmpty(),
            stateKey = editedProduct.id,
            // Editing: the product's section already stands for a decision —
            // "no section" included — so nothing here may replace it with a
            // guess from the name. The line under the field still says what the
            // name would imply, and picking it stays one tap away.
            suggestSection = false,
            // Renaming into another product's name would make the same pair of
            // duplicates that creating one does; the product keeps its own name.
            nameConflictOf = { typed ->
                productNameConflict(
                    state.products,
                    state.archivedProducts,
                    typed,
                    selfId = editedProduct.id,
                )
            },
            onConfirm = { name, quantity, unit, minQuantity, notes, emoji, expiresOn, added, removed ->
                viewModel.updateProduct(
                    editedProduct.id, name, quantity, unit, minQuantity, notes, emoji, expiresOn,
                    addedBarcodes = added,
                    removedBarcodes = removed,
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
            // Only an archived product no list refers to can go for good; an
            // item anywhere (even on an archived list) would be deleted with
            // it. The repository checks again — this only decides what to show.
            onDelete = if (
                editedArchived != null &&
                editedProduct.id !in state.referencedProductIds
            ) {
                {
                    viewModel.deleteArchived(editedProduct.id)
                    editedProductId = null
                }
            } else {
                null
            },
        )
    }
}
