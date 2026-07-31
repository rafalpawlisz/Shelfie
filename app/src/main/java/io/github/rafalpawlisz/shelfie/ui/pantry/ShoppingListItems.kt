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
import io.github.rafalpawlisz.shelfie.model.ProductCategory
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
        // Section headers sit between the rows now, so LazyColumn indices no
        // longer line up with [ordered] — keys do. A drag applies only between
        // two rows of the same section: the section is the product's, not the
        // row's, so crossing a header could never stick (the sort would put
        // the row straight back), and the checked block at the bottom is not
        // draggable territory either.
        val fromIndex = ordered.indexOfFirst { it.id == from.key }
        val toIndex = ordered.indexOfFirst { it.id == to.key }
        if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
        val a = ordered[fromIndex]
        val b = ordered[toIndex]
        if (b.isChecked || sectionOf(a) != sectionOf(b)) return@rememberReorderableLazyListState
        ordered.add(toIndex, ordered.removeAt(fromIndex))
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
            // The unchecked block walks the store section by section with a
            // small header over each group; sectionless rows (one-offs, old
            // emoji, none) trail as their own group. The checked block keeps
            // its own single header: the cart is not an aisle, but without a
            // label of its own it butted straight against "No section" and
            // read as more of it.
            val headed = buildList {
                var previousKey: Any? = Unit // never equals a section, null or Cart
                for (item in ordered) {
                    val key = if (item.isChecked) Cart else sectionOf(item)
                    if (key != previousKey) {
                        add(
                            if (key == Cart) {
                                HeaderOrItem.CartHeader
                            } else {
                                HeaderOrItem.Header(key as ProductCategory?)
                            },
                        )
                    }
                    previousKey = key
                    add(HeaderOrItem.Row(item))
                }
            }
            items(
                headed,
                key = { entry ->
                    when (entry) {
                        is HeaderOrItem.Header -> "header-${entry.section?.name ?: "none"}"
                        HeaderOrItem.CartHeader -> "header-cart"
                        is HeaderOrItem.Row -> entry.item.id
                    }
                },
            ) { entry ->
                if (entry is HeaderOrItem.Header) {
                    SectionHeader(entry.section)
                    return@items
                }
                if (entry is HeaderOrItem.CartHeader) {
                    GroupHeader(stringResource(R.string.cart_section))
                    return@items
                }
                val item = (entry as HeaderOrItem.Row).item
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
                            if (!item.isChecked && item.amount == null && item.productId != null) {
                                // No amount recorded — ask how many were bought,
                                // because checkout banks it into stock. A one-off
                                // has no stock, so there is nothing to ask.
                                checkingItemId = item.id
                            } else {
                                onToggle(item.id, !item.isChecked)
                            }
                        },
                        onRemove = { onRemove(item.id) },
                        onEditAmount = { editingAmountItemId = item.id },
                        // One-offs have no product slot to remember a position
                        // for; hiding the handle says so instead of offering a
                        // drag that would snap back.
                        dragHandleModifier = if (item.productId != null) handleModifier else null,
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
    // null hides the handle: one-off items have no position to drag.
    dragHandleModifier: Modifier?,
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
                if (dragHandleModifier != null) {
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
}

// One flat LazyColumn feeds both headers and rows, so the reorderable state
// sees stable keys; the section of a row is derived from its product's emoji.
private sealed interface HeaderOrItem {
    data class Header(val section: ProductCategory?) : HeaderOrItem
    data object CartHeader : HeaderOrItem
    data class Row(val item: ShoppingListItem) : HeaderOrItem
}

// The grouping key for checked rows. A object of its own rather than null or
// Unit: null is a real answer here (the sectionless group) and Unit was the
// "nothing yet" seed, so reusing either would silently merge two groups.
private data object Cart

// null covers one-offs, pre-section emoji and "no section" alike.
private fun sectionOf(item: ShoppingListItem): ProductCategory? =
    ProductCategory.fromEmoji(item.productEmoji)
