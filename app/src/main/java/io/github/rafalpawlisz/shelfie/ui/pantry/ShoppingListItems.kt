package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import io.github.rafalpawlisz.shelfie.ui.DragHandleIcon
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun ListItems(
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
    // the upstream order actually changes (e.g. after a move is persisted) — but
    // never mid-drag, or an emission from the household would rebuild the list
    // under the finger and snap the row back. Keyed on the flag too, so the
    // mirror catches up the moment the gesture ends.
    var draggingRow by remember { mutableStateOf(false) }
    val ordered = remember { mutableStateListOf<ShoppingListItem>().apply { addAll(items) } }
    val lazyListState = rememberLazyListState()
    LaunchedEffect(items, draggingRow) {
        if (!draggingRow && ordered != items) {
            ordered.clear()
            ordered.addAll(items)
            // Keep the viewport where it is. A keyed LazyColumn anchors scroll
            // to the first visible ITEM, so when checking off the top row sent
            // it to the bottom (checked items park there), the list obediently
            // followed it down. Re-request the same index/offset for the next
            // layout: the position stays, whatever moved underneath.
            lazyListState.requestScrollToItem(
                lazyListState.firstVisibleItemIndex,
                lazyListState.firstVisibleItemScrollOffset,
            )
        }
    }
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
                        onDragStarted = {
                            draggingRow = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragStopped = {
                            val from = items.indexOfFirst { it.id == item.id }
                            val to = ordered.indexOfFirst { it.id == item.id }
                            if (from != -1 && to != -1 && from != to) onMove(from, to)
                            draggingRow = false
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
