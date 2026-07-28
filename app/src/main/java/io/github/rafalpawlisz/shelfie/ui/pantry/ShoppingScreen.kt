package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem

@Composable
fun ShoppingScreen(
    lists: List<ShoppingList>,
    archivedLists: List<ShoppingList>,
    selectedListId: String?,
    items: List<ShoppingListItem>,
    lowStockProducts: List<Product>,
    plannedByProduct: Map<String, Set<String>>,
    onSelectList: (String) -> Unit,
    onCreateList: (String) -> Unit,
    onRenameList: (id: String, name: String) -> Unit,
    onArchiveList: (id: String) -> Unit,
    onRestoreList: (id: String) -> Unit,
    onDeleteList: (id: String) -> Unit,
    onMoveList: (fromIndex: Int, toIndex: Int) -> Unit,
    onToggle: (id: String, checked: Boolean) -> Unit,
    onRemove: (id: String) -> Unit,
    onUpdateItem: (id: String, amount: Int?, note: String?, targetListId: String?) -> Unit,
    onCheckWithAmount: (id: String, amount: Int) -> Unit,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onRestockProduct: (Product) -> Unit,
    onAddAllLowStock: (listId: String) -> Unit,
    onFinishShopping: () -> Unit,
) {
    // Viewing the derived "low stock" pseudo-list; purely presentational, the
    // real list selection in the ViewModel stays untouched.
    var lowStockSelected by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lowStockProducts.isEmpty()) {
        if (lowStockProducts.isEmpty()) lowStockSelected = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ListChipsRow(
            lists = lists,
            archivedLists = archivedLists,
            selectedListId = if (lowStockSelected) null else selectedListId,
            lowStockCount = lowStockProducts.size,
            lowStockSelected = lowStockSelected,
            onSelectLowStock = { lowStockSelected = true },
            onSelectList = {
                lowStockSelected = false
                onSelectList(it)
            },
            onCreateList = onCreateList,
            onRenameList = onRenameList,
            onArchiveList = onArchiveList,
            onRestoreList = onRestoreList,
            onDeleteList = onDeleteList,
            onMoveList = onMoveList,
        )
        when {
            lowStockSelected -> LowStockView(
                products = lowStockProducts,
                lists = lists,
                onRestockProduct = onRestockProduct,
                onAddAll = onAddAllLowStock,
            )
            lists.isEmpty() -> Box(modifier = Modifier.fillMaxSize()) {
                EmptyState(
                    title = stringResource(R.string.shopping_no_lists_title),
                    message = stringResource(R.string.shopping_no_lists_message),
                    modifier = Modifier.align(Alignment.Center),
                    icon = Icons.Default.ShoppingCart,
                )
            }
            items.isEmpty() -> Box(modifier = Modifier.fillMaxSize()) {
                EmptyState(
                    title = stringResource(R.string.shopping_empty_title),
                    message = stringResource(R.string.shopping_empty_message),
                    modifier = Modifier.align(Alignment.Center),
                    icon = Icons.Default.ShoppingCart,
                )
            }
            else -> ListItems(
                items = items,
                lists = lists,
                currentListId = selectedListId,
                plannedByProduct = plannedByProduct,
                onToggle = onToggle,
                onRemove = onRemove,
                onUpdateItem = onUpdateItem,
                onCheckWithAmount = onCheckWithAmount,
                onMove = onMove,
                onFinishShopping = onFinishShopping,
            )
        }
    }
}
