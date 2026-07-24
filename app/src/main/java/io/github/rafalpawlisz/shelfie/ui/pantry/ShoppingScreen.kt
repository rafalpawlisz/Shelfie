package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import io.github.rafalpawlisz.shelfie.ui.DragHandleIcon
import io.github.rafalpawlisz.shelfie.ui.theme.warning
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
                )
            }
            items.isEmpty() -> Box(modifier = Modifier.fillMaxSize()) {
                EmptyState(
                    title = stringResource(R.string.shopping_empty_title),
                    message = stringResource(R.string.shopping_empty_message),
                    modifier = Modifier.align(Alignment.Center),
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

// Read-only view of the products below their minimum: tapping a row opens the
// restock dialog (store picker); "Add all" sends every shortage to one list.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LowStockView(
    products: List<Product>,
    lists: List<ShoppingList>,
    onRestockProduct: (Product) -> Unit,
    onAddAll: (listId: String) -> Unit,
) {
    var showAddAllDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.low_stock_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (lists.isNotEmpty()) {
                TextButton(onClick = { showAddAllDialog = true }) {
                    Text(stringResource(R.string.action_add_all))
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(products, key = { it.id }) { product ->
                ProductListItem(
                    product = product,
                    onClick = { onRestockProduct(product) },
                )
            }
        }
    }

    if (showAddAllDialog) {
        var targetListId by rememberSaveable { mutableStateOf(lists.firstOrNull()?.id) }
        AlertDialog(
            onDismissRequest = { showAddAllDialog = false },
            title = { Text(stringResource(R.string.add_to_shopping_list)) },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lists.forEach { list ->
                        FilterChip(
                            selected = list.id == targetListId,
                            onClick = { targetListId = list.id },
                            label = { Text(list.name) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = lists.any { it.id == targetListId },
                    onClick = {
                        onAddAll(targetListId!!)
                        showAddAllDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAllDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListChipsRow(
    lists: List<ShoppingList>,
    archivedLists: List<ShoppingList>,
    selectedListId: String?,
    lowStockCount: Int,
    lowStockSelected: Boolean,
    onSelectLowStock: () -> Unit,
    onSelectList: (String) -> Unit,
    onCreateList: (String) -> Unit,
    onRenameList: (id: String, name: String) -> Unit,
    onArchiveList: (id: String) -> Unit,
    onRestoreList: (id: String) -> Unit,
    onDeleteList: (id: String) -> Unit,
    onMoveList: (fromIndex: Int, toIndex: Int) -> Unit,
) {
    var menuListId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var renamingListId by rememberSaveable { mutableStateOf<String?>(null) }
    var showArchiveDialog by rememberSaveable { mutableStateOf(false) }
    var deletingArchivedId by rememberSaveable { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current

    // Local mirror so a drag animates smoothly; re-synced from [lists] when the
    // upstream order changes (after a move is persisted), but not mid-drag.
    val orderedLists = remember { mutableStateListOf<ShoppingList>().apply { addAll(lists) } }
    LaunchedEffect(lists) {
        if (orderedLists != lists) {
            orderedLists.clear()
            orderedLists.addAll(lists)
        }
    }
    val lazyListState = rememberLazyListState()
    // The pinned low-stock chip (when present) shifts every LazyRow index by one.
    val chipOffset = if (lowStockCount > 0) 1 else 0
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Only the list chips reorder; the pinned low-stock chip stays first and
        // the trailing +/Archive chips stay at the end.
        if (to.index >= chipOffset && to.index < chipOffset + orderedLists.size) {
            orderedLists.add(to.index - chipOffset, orderedLists.removeAt(from.index - chipOffset))
        }
    }

    LazyRow(
        state = lazyListState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lowStockCount > 0) {
            // Derived "low stock" pseudo-list; pinned before the real, draggable chips.
            item(key = "low-stock") {
                FilterChip(
                    selected = lowStockSelected,
                    onClick = onSelectLowStock,
                    label = {
                        Text(
                            text = stringResource(R.string.low_stock_list, lowStockCount),
                            color = if (lowStockSelected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.warning
                            },
                        )
                    },
                )
            }
        }
        items(orderedLists, key = { it.id }) { list ->
            ReorderableItem(reorderableState, key = list.id) { _ ->
                val selected = list.id == selectedListId
                // The whole chip is the drag handle on long-press; a tap still selects.
                val handleModifier = Modifier.longPressDraggableHandle(
                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onDragStopped = {
                        val from = lists.indexOfFirst { it.id == list.id }
                        val to = orderedLists.indexOfFirst { it.id == list.id }
                        if (from != -1 && to != -1 && from != to) onMoveList(from, to)
                    },
                )
                Box {
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectList(list.id) },
                        label = { Text(list.name) },
                        modifier = handleModifier,
                        trailingIcon = if (selected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.cd_list_menu, list.name),
                                    modifier = Modifier.clickable { menuListId = list.id },
                                )
                            }
                        } else {
                            null
                        },
                    )
                    DropdownMenu(
                        expanded = menuListId == list.id,
                        onDismissRequest = { menuListId = null },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename)) },
                            onClick = { menuListId = null; renamingListId = list.id },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_archive)) },
                            onClick = { menuListId = null; onArchiveList(list.id) },
                        )
                    }
                }
            }
        }
        item {
            AssistChip(
                onClick = { showCreateDialog = true },
                label = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.create_list),
                    )
                },
            )
        }
        if (archivedLists.isNotEmpty()) {
            item {
                AssistChip(
                    onClick = { showArchiveDialog = true },
                    label = { Text(stringResource(R.string.archived_short, archivedLists.size)) },
                )
            }
        }
    }

    if (showCreateDialog) {
        ListNameDialog(
            title = stringResource(R.string.create_list),
            initialName = "",
            confirmLabel = stringResource(R.string.action_create),
            onConfirm = { onCreateList(it); showCreateDialog = false },
            onDismiss = { showCreateDialog = false },
        )
    }

    val renaming = lists.firstOrNull { it.id == renamingListId }
    if (renaming != null) {
        ListNameDialog(
            title = stringResource(R.string.rename_list),
            initialName = renaming.name,
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { onRenameList(renaming.id, it); renamingListId = null },
            onDismiss = { renamingListId = null },
        )
    }

    // Archive view: restore a list, or step into a confirmed permanent delete.
    // Guarded on isNotEmpty() so it closes itself once the archive is emptied.
    if (showArchiveDialog && archivedLists.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            confirmButton = {
                TextButton(onClick = { showArchiveDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            title = { Text(stringResource(R.string.archived_lists)) },
            text = {
                Column {
                    archivedLists.forEach { list ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = list.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onRestoreList(list.id) }) {
                                Text(stringResource(R.string.action_restore))
                            }
                            TextButton(onClick = { deletingArchivedId = list.id }) {
                                Text(stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            },
        )
    }

    val deletingArchived = archivedLists.firstOrNull { it.id == deletingArchivedId }
    if (deletingArchived != null) {
        AlertDialog(
            onDismissRequest = { deletingArchivedId = null },
            confirmButton = {
                TextButton(
                    onClick = { onDeleteList(deletingArchived.id); deletingArchivedId = null },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingArchivedId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            text = { Text(stringResource(R.string.delete_list_message, deletingArchived.name)) },
        )
    }
}

// Row-tap edit: amount (blank = "just buy it"), the one-off shopping note
// (blank = none), and — when there is more than one list — the list the item
// belongs to. Picking a different list moves the item there on save.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemEditDialog(
    lists: List<ShoppingList>,
    currentListId: String?,
    unavailableListIds: Set<String>,
    initialAmount: Int?,
    initialNote: String?,
    onConfirm: (amount: Int?, note: String?, targetListId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var amountText by rememberSaveable { mutableStateOf(initialAmount?.toString().orEmpty()) }
    var noteText by rememberSaveable { mutableStateOf(initialNote.orEmpty()) }
    var targetListId by rememberSaveable { mutableStateOf(currentListId) }
    val amount = amountText.trim().toIntOrNull()
    val isValid = amountText.isBlank() || (amount != null && amount > 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_item)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lists.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        lists.forEach { list ->
                            FilterChip(
                                selected = list.id == targetListId,
                                // A list that already plans this product is not a
                                // valid move target.
                                enabled = list.id !in unavailableListIds,
                                onClick = { targetListId = list.id },
                                label = { Text(list.name) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(stringResource(R.string.shopping_amount_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(stringResource(R.string.product_notes_label)) },
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    onConfirm(
                        if (amountText.isBlank()) null else amount,
                        noteText.trim().ifBlank { null },
                        targetListId?.takeIf { it != currentListId },
                    )
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// [allowEmpty]: a blank field is valid and confirms null ("just buy it") when
// editing; the check-off variant requires a number (stock math needs it).
@Composable
private fun AmountDialog(
    title: String,
    initialAmount: Int?,
    allowEmpty: Boolean,
    onConfirm: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var amountText by rememberSaveable { mutableStateOf(initialAmount?.toString().orEmpty()) }
    val amount = amountText.trim().toIntOrNull()
    val isValid = if (amountText.isBlank()) allowEmpty else amount != null && amount > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(stringResource(R.string.shopping_amount_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onConfirm(if (amountText.isBlank()) null else amount) },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ListNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.list_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ListItems(
    items: List<ShoppingListItem>,
    lists: List<ShoppingList>,
    currentListId: String?,
    plannedByProduct: Map<String, Set<String>>,
    onToggle: (id: String, checked: Boolean) -> Unit,
    onRemove: (id: String) -> Unit,
    onUpdateItem: (id: String, amount: Int?, note: String?, targetListId: String?) -> Unit,
    onCheckWithAmount: (id: String, amount: Int) -> Unit,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onFinishShopping: () -> Unit,
) {
    val checkedCount = items.count { it.isChecked }
    var showFinishDialog by rememberSaveable { mutableStateOf(false) }
    var editingAmountItemId by rememberSaveable { mutableStateOf<String?>(null) }
    // Item being checked off that has no amount yet — the dialog asks how many
    // were actually bought so checkout can bank it into stock.
    var checkingItemId by rememberSaveable { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current

    // Local mirror so a drag animates smoothly. It re-syncs from [items] whenever
    // the upstream order actually changes (e.g. after a move is persisted), but
    // not mid-drag — nothing is persisted until the gesture ends.
    val ordered = remember { mutableStateListOf<ShoppingListItem>().apply { addAll(items) } }
    LaunchedEffect(items) {
        if (ordered != items) {
            ordered.clear()
            ordered.addAll(items)
        }
    }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Only unchecked items are manually ordered; keep the drag within that
        // block so the checked items parked at the bottom aren't displaced.
        val uncheckedCount = ordered.count { !it.isChecked }
        if (to.index < uncheckedCount) {
            ordered.add(to.index, ordered.removeAt(from.index))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Hint and the (conditional) finish action share one fixed-height row so
        // the button appearing/disappearing never shifts the list below.
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.shopping_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (checkedCount > 0) {
                TextButton(onClick = { showFinishDialog = true }) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = null)
                    Text(text = stringResource(R.string.finish_shopping, checkedCount))
                }
            }
        }
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            // Extra bottom padding keeps the FAB clear of the last row.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ordered, key = { it.id }) { item ->
                ReorderableItem(reorderableState, key = item.id) { _ ->
                    // Built inside the reorderable scope so draggableHandle binds
                    // correctly, then handed to the row as a plain Modifier. On
                    // drop, translate the net move into indices over the upstream
                    // list so the ViewModel can persist the moved item's position.
                    val handleModifier = Modifier.draggableHandle(
                        onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragStopped = {
                            val from = items.indexOfFirst { it.id == item.id }
                            val to = ordered.indexOfFirst { it.id == item.id }
                            if (from != -1 && to != -1 && from != to) onMove(from, to)
                        },
                    )
                    ShoppingListRow(
                        item = item,
                        onToggle = {
                            if (!item.isChecked && item.amount == null) {
                                // No amount recorded — ask how many were bought.
                                checkingItemId = item.id
                            } else {
                                onToggle(item.id, !item.isChecked)
                            }
                        },
                        onRemove = { onRemove(item.id) },
                        onEditAmount = { editingAmountItemId = item.id },
                        dragHandleModifier = handleModifier,
                    )
                }
            }
        }
    }

    val editingItem = items.firstOrNull { it.id == editingAmountItemId }
    if (editingItem != null) {
        ItemEditDialog(
            lists = lists,
            currentListId = currentListId,
            // Lists that already plan this product can't be a move target — a
            // move must never silently clobber an existing entry.
            unavailableListIds = plannedByProduct[editingItem.productId]
                .orEmpty()
                .minus(currentListId.orEmpty()),
            initialAmount = editingItem.amount,
            initialNote = editingItem.note,
            onConfirm = { amount, note, targetListId ->
                onUpdateItem(editingItem.id, amount, note, targetListId)
                editingAmountItemId = null
            },
            onDismiss = { editingAmountItemId = null },
        )
    }

    val checkingItem = items.firstOrNull { it.id == checkingItemId }
    if (checkingItem != null) {
        AmountDialog(
            title = stringResource(R.string.check_amount_title),
            initialAmount = 1,
            allowEmpty = false, // checkout math needs a number
            onConfirm = { amount ->
                if (amount != null) onCheckWithAmount(checkingItem.id, amount)
                checkingItemId = null
            },
            onDismiss = { checkingItemId = null },
        )
    }

    if (showFinishDialog) {
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFinishDialog = false
                        onFinishShopping()
                    },
                ) {
                    Text(stringResource(R.string.action_finish))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinishDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.finish_shopping_message,
                        checkedCount,
                        checkedCount,
                    ),
                )
            },
        )
    }
}

@Composable
private fun ShoppingListRow(
    item: ShoppingListItem,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onEditAmount: () -> Unit,
    dragHandleModifier: Modifier,
) {
    val textColor =
        if (item.isChecked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
    val decoration = if (item.isChecked) TextDecoration.LineThrough else null

    // The checkbox toggles (its natural role, with a 48dp touch target); tapping
    // the rest of the row edits the amount.
    Card(onClick = onEditAmount, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = item.isChecked, onCheckedChange = { onToggle() })
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    text = listOfNotNull(item.productEmoji, item.productName).joinToString(" "),
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor,
                    textDecoration = decoration,
                )
                // No amount recorded = "just buy it" — only the name shows.
                if (item.amount != null) {
                    Text(
                        text = item.productUnit
                            ?.let { stringResource(R.string.shopping_amount_with_unit, item.amount, it) }
                            ?: stringResource(R.string.shopping_amount, item.amount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        textDecoration = decoration,
                    )
                }
                // One-off shopping note; dies with the item at checkout.
                if (item.note != null) {
                    Text(
                        text = item.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isChecked) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textDecoration = decoration,
                    )
                }
            }
            // Remove and drag handle only on unchecked (to-buy) rows. Checked items
            // are parked at the bottom ordered by check time, so no manual handle;
            // the drag grip (built in the reorderable item scope) makes it draggable
            // while the row's own tap still toggles the checkbox.
            if (!item.isChecked) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription =
                            stringResource(R.string.cd_remove_from_list, item.productName),
                    )
                }
                IconButton(modifier = dragHandleModifier, onClick = {}) {
                    Icon(
                        imageVector = DragHandleIcon,
                        contentDescription = stringResource(R.string.cd_drag_handle),
                    )
                }
            }
        }
    }
}
