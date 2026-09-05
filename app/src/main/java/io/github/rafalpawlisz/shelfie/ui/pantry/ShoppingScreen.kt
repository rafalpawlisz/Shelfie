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
import io.github.rafalpawlisz.shelfie.model.ProductCategory
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import kotlinx.coroutines.flow.Flow

@Composable
fun ShoppingScreen(
    lists: List<ShoppingList>,
    archivedLists: List<ShoppingList>,
    emptyListIds: Set<String>,
    selectedListId: String?,
    items: List<ShoppingListItem>,
    lowStockProducts: List<Product>,
    plannedByProduct: Map<String, Set<String>>,
    onSelectList: (String) -> Unit,
    onCreateList: (String) -> Unit,
    onRenameList: (id: String, name: String) -> Unit,
    onSetSectionOrder: (id: String, order: List<ProductCategory>) -> Unit,
    onArchiveList: (id: String) -> Unit,
    onRestoreList: (id: String) -> Unit,
    onDeleteList: (id: String) -> Unit,
    onMoveList: (draggedId: String, beforeId: String?) -> Unit,
    onToggle: (id: String, checked: Boolean) -> Unit,
    onRemove: (id: String) -> Unit,
    onUpdateItem: (
        id: String,
        amount: Int?,
        unit: String?,
        note: String?,
        targetListId: String?,
        sectionEmoji: String?,
    ) -> Unit,
    onCheckWithAmount: (id: String, amount: Int) -> Unit,
    onMove: (fromIndex: Int, toIndex: Int) -> Boolean,
    onRestockProduct: (Product) -> Unit,
    onAddAllLowStock: (listId: String) -> Unit,
    onFinishShopping: () -> Unit,
    // One-shot "a row was added from the picker" events; ListItems reveals
    // the row. Collected down there, where the list is on screen — a target
    // armed in a parent would outlive the screen and could fire on a list
    // nobody is looking at.
    itemAddedEvents: Flow<AddedShoppingItem>,
    // Held by ShelfieApp so hopping tabs does not reset the choice.
    hideEmptyLists: Boolean,
    onToggleHideEmpty: () -> Unit,
) {
    // Viewing the derived "low stock" pseudo-list; purely presentational, the
    // real list selection in the ViewModel stays untouched.
    var lowStockSelected by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lowStockProducts.isEmpty()) {
        if (lowStockProducts.isEmpty()) lowStockSelected = false
    }

    val visibleLists = if (hideEmptyLists) {
        // The list being viewed stays put — the chip is the "you are here"
        // marker, and a just-created empty list must not vanish the moment it
        // is created; it hides once the user leaves it.
        lists.filterNot { it.id in emptyListIds && it.id != selectedListId }
    } else {
        lists
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ListChipsRow(
            lists = visibleLists,
            // Full order, hidden lists included: a drag dropped at the end of
            // the visible row must not skip over the hidden lists trailing it.
            allLists = lists,
            archivedLists = archivedLists,
            selectedListId = if (lowStockSelected) null else selectedListId,
            lowStockCount = lowStockProducts.size,
            lowStockSelected = lowStockSelected,
            hideEmptyLists = hideEmptyLists,
            onToggleHideEmpty = onToggleHideEmpty,
            onSelectLowStock = { lowStockSelected = true },
            onSelectList = {
                lowStockSelected = false
                onSelectList(it)
            },
            onCreateList = onCreateList,
            onRenameList = onRenameList,
            onSetSectionOrder = onSetSectionOrder,
            onArchiveList = onArchiveList,
            onRestoreList = onRestoreList,
            onDeleteList = onDeleteList,
            onMoveList = onMoveList,
        )
        when {
            lowStockSelected -> LowStockView(
                products = lowStockProducts,
                // All lists, hidden ones included: "add all" lands on an empty
                // list as happily as on a full one, and revives it.
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
                // All lists, hidden ones included: the move picker may target a
                // list the chips row hides — moving an item there revives it.
                lists = lists,
                currentListId = selectedListId,
                plannedByProduct = plannedByProduct,
                onToggle = onToggle,
                onRemove = onRemove,
                onUpdateItem = onUpdateItem,
                onCheckWithAmount = onCheckWithAmount,
                onMove = onMove,
                onFinishShopping = onFinishShopping,
                itemAddedEvents = itemAddedEvents,
            )
        }
    }
}
