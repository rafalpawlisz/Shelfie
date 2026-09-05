package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    onUpdateItem: (
        id: String,
        amount: Int?,
        unit: String?,
        note: String?,
        targetListId: String?,
        sectionEmoji: String?,
    ) -> Unit,
    onCheckWithAmount: (id: String, amount: Int) -> Unit,
    // Returns whether the move was accepted and a write dispatched; the drag
    // mirror holds the dropped order only for a move that will echo back.
    onMove: (fromIndex: Int, toIndex: Int) -> Boolean,
    onFinishShopping: () -> Unit,
    // One-shot "a row was added from the picker" events; the row is scrolled
    // into view once Room hands it back. Collected here — where the list is
    // on screen — so a target dies with the composition: an add made while
    // the list was not visible (low-stock view, another tab) cannot reveal
    // later, and switching lists drops a pending reveal.
    itemAddedEvents: Flow<AddedShoppingItem>,
) {
    val checkedCount = items.count { it.isChecked }
    var showFinishDialog by rememberSaveable { mutableStateOf(false) }
    var editingAmountItemId by rememberSaveable { mutableStateOf<String?>(null) }
    // Item being checked off that has no amount yet — the dialog asks how many
    // were actually bought so checkout can bank it into stock.
    var checkingItemId by rememberSaveable { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current

    // Local mirror so a drag animates smoothly. It re-syncs from [items] when the
    // upstream order changes — but never mid-drag, or an emission from the
    // household would rebuild the list under the finger and snap the row back.
    var draggingRow by remember { mutableStateOf(false) }
    // The dragged row's section, meaningful only while [draggingRow]. It decides
    // which OTHER rows count as drop targets: the library assumes every target
    // it picks will actually be swapped with (it predicts the dragged row's new
    // offset and waits up to a second for the layout to confirm), so a target
    // whose swap we would refuse must never enter the candidate set at all —
    // refusing in the callback left the row drawn at the slot it never took,
    // visibly jumping away from the finger at the aisle boundary.
    var draggedSection by remember { mutableStateOf<ProductCategory?>(null) }
    // Set on a drop that was handed to the ViewModel, cleared by the emission
    // that carries it back. In between, the mirror is AHEAD of Room: re-syncing
    // from the list we just dragged away from put the row back where it started
    // and then animated it into place a moment later, which reads as the row
    // hopping somewhere by itself after you let go.
    var awaitingDrop by remember { mutableStateOf(false) }
    val ordered = remember { mutableStateListOf<ShoppingListItem>().apply { addAll(items) } }
    val lazyListState = rememberLazyListState()
    fun resyncFromUpstream() {
        if (ordered == items) return
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
    LaunchedEffect(items) {
        // Whatever this emission holds, the reorder round-trip is over: either it
        // is our own drop coming back (a no-op resync) or a newer truth to take.
        awaitingDrop = false
        if (!draggingRow) resyncFromUpstream()
    }
    LaunchedEffect(draggingRow) {
        // The gesture ended without a persisted move (a drag that went nowhere,
        // or one the ViewModel declined): the mirror may hold a rearrangement
        // nothing will confirm, so take the upstream order back.
        if (!draggingRow && !awaitingDrop) resyncFromUpstream()
    }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Section headers sit between the rows now, so LazyColumn indices no
        // longer line up with [ordered] — keys do. A drag applies only between
        // two rows of the same section: the section is the product's, not the
        // row's, so crossing a header could never stick (the sort would put
        // the row straight back), and the checked block at the bottom is not
        // draggable territory either. Per-item `enabled` keeps such targets out
        // of the library's sight before it gets here; this guard is what makes
        // the invariant hold even if that filter and this callback disagree.
        val fromIndex = ordered.indexOfFirst { it.id == from.key }
        val toIndex = ordered.indexOfFirst { it.id == to.key }
        if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
        val a = ordered[fromIndex]
        val b = ordered[toIndex]
        if (b.isChecked || sectionOf(a) != sectionOf(b)) return@rememberReorderableLazyListState
        ordered.add(toIndex, ordered.removeAt(fromIndex))
    }

    // One flat list feeds both headers and rows, so the reorderable state sees
    // stable keys; built once per composition rather than inside the
    // LazyColumn. The reveal effect calls sectionedEntries itself (a captured
    // build would be stale after its wait), and both derive the same grouping
    // from the same live mirror.
    val headed = sectionedEntries(ordered)

    // The just-added row's highlight; set by the reveal effect once the row is
    // on screen, cleared by the effect below after it has had a beat to fade.
    var flashItemId by remember { mutableStateOf<String?>(null) }

    // A row the picker just added on this list, waiting for its reveal.
    // Local rather than hoisted: leaving this composition drops it, so a
    // reveal can never fire on a list the user stopped looking at.
    var revealItemId by remember { mutableStateOf<String?>(null) }
    val shownListId by rememberUpdatedState(currentListId)
    LaunchedEffect(itemAddedEvents) {
        // Only events for THIS list arm a reveal, and only fresh ones: the
        // channel keeps events sent while this screen was not composed (the
        // picker stays reachable from the low-stock view), and replaying one
        // on return would scroll to a row the user added long ago.
        itemAddedEvents.collect { added ->
            if (
                added.listId == shownListId &&
                System.currentTimeMillis() - added.sentAtMillis < REVEAL_FRESHNESS_MILLIS
            ) {
                revealItemId = added.itemId
            }
        }
    }
    LaunchedEffect(currentListId) {
        // A pending reveal belongs to the list it was added to; switching
        // lists mid-flight drops it instead of flashing the old list's row
        // on the new one.
        revealItemId = null
    }

    // Reveal a row the picker just added: wait until the mirror holds it
    // where the list shows it — present and unchecked, in its aisle. A row
    // that is present but checked is a re-add that merged into a row still
    // parked in the cart: the merge unchecks it a beat later, and anchoring
    // to the cart slot would scroll to a row that has already flown back to
    // its aisle. Then anchor the viewport to the row — or its section
    // header, when the row opens the group, so the reveal lands with the
    // aisle label above it. Cleared at the end, after the scroll: a null
    // here restarts this effect (its key changed) and would cancel the
    // animation mid-flight.
    LaunchedEffect(revealItemId) {
        val target = revealItemId ?: return@LaunchedEffect
        snapshotFlow { ordered.indexOfFirst { it.id == target && !it.isChecked } }
            .first { it != -1 }
        val entries = sectionedEntries(ordered)
        val rowEntry = entries.indexOfFirst {
            it is HeaderOrItem.Row && it.item.id == target
        }
        val anchor = if (rowEntry > 0 && entries[rowEntry - 1] is HeaderOrItem.Header) {
            rowEntry - 1
        } else {
            rowEntry
        }
        lazyListState.animateScrollToItem(anchor)
        // The scroll alone shows WHERE the row is; the tint says WHICH row is
        // new when the list barely moved (an item edited in place) or the row
        // was already on screen.
        flashItemId = target
        revealItemId = null
    }

    // Let the flash run its course. Keyed on the id: a second add mid-flash
    // restarts the countdown and the older row simply stops flashing.
    LaunchedEffect(flashItemId) {
        delay(ADDED_ROW_FLASH_HOLD_MILLIS)
        flashItemId = null
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
                ReorderableItem(
                    reorderableState,
                    key = item.id,
                    // A row is a drop target only when a drop on it could stick:
                    // unchecked, and — while something is being dragged — of the
                    // dragged row's own aisle. See [draggedSection].
                    enabled = !item.isChecked &&
                        (!draggingRow || sectionOf(item) == draggedSection),
                ) { _ ->
                    // Built inside the reorderable scope so draggableHandle binds
                    // correctly, then handed to the row as a plain Modifier. On
                    // drop, translate the net move into indices over the upstream
                    // list so the ViewModel can persist the moved item's position.
                    val handleModifier = Modifier.draggableHandle(
                        onDragStarted = {
                            draggedSection = sectionOf(item)
                            draggingRow = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragStopped = {
                            val from = items.indexOfFirst { it.id == item.id }
                            val to = ordered.indexOfFirst { it.id == item.id }
                            if (from != -1 && to != -1 && from != to) {
                                // Armed before the flag that ends the drag, so
                                // the mirror is never re-synced from the order
                                // this move is about to replace — but only for
                                // a move the ViewModel accepted. A declined one
                                // (stale indices under a mid-drag household
                                // emission, a drop outside the aisle) echoes
                                // nothing back, and a mirror waiting for that
                                // echo would show the unpersisted order until
                                // some unrelated change happened to land.
                                awaitingDrop = onMove(from, to)
                            }
                            draggingRow = false
                        },
                    )
                    ShoppingListRow(
                        item = item,
                        highlighted = item.id == flashItemId,
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
                        // Checked rows are parked at the bottom by check time, so
                        // dragging one could only snap back; everything still to
                        // buy can be placed, one-offs included.
                        dragHandleModifier = if (!item.isChecked) handleModifier else null,
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
            // A product row's unit belongs to the product, so only a one-off
            // brings one in here to edit.
            initialUnit = editingItem.productUnit.takeIf { editingItem.productId == null },
            isOneOff = editingItem.productId == null,
            name = editingItem.productName,
            // The raw pick, not the section on show: telling them apart is what
            // stops an amount edit from freezing a guess.
            initialSectionEmoji = editingItem.sectionEmoji,
            initialNote = editingItem.note,
            onConfirm = { amount, unit, note, targetListId, sectionEmoji ->
                onUpdateItem(editingItem.id, amount, unit, note, targetListId, sectionEmoji)
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
    // True right after the picker added this row: the card keeps a highlight
    // for a moment, then fades back to its resting color.
    highlighted: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onEditAmount: () -> Unit,
    // Not the row's own modifier — it belongs to the drag handle inside, and is
    // built in the reorderable scope the row itself has no access to. null hides
    // the handle: a checked row is parked by check time and cannot be placed.
    @Suppress("ModifierParameter") dragHandleModifier: Modifier?,
) {
    val textColor =
        if (item.isChecked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
    val decoration = if (item.isChecked) TextDecoration.LineThrough else null
    val containerColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            CardDefaults.cardColors().containerColor
        },
        animationSpec = tween(ADDED_ROW_FLASH_FADE_MILLIS),
        label = "addedRowFlash",
    )

    // The checkbox toggles (its natural role, with a 48dp touch target); tapping
    // the rest of the row edits the amount.
    Card(
        onClick = onEditAmount,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = item.isChecked, onCheckedChange = { onToggle() })
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    // Same rule as the pantry: the section is in the header
                    // above, so the row wears the product's own emoji.
                    text = listOfNotNull(decorationFor(item.productName), item.productName)
                        .joinToString(" "),
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

/**
 * The list's rows with the small header that opens each group, flattened in
 * display order. The unchecked block walks the store section by section with
 * a header over every group — sectionless rows (one-offs, old emoji, none)
 * get their own; the checked block keeps one header of its own, because the
 * cart is not an aisle, but without a label of its own it butted straight
 * against "No section" and read as more of it. Shared by the LazyColumn and
 * the reveal lookup, so a scroll index always means what the list shows.
 */
private fun sectionedEntries(ordered: List<ShoppingListItem>): List<HeaderOrItem> =
    buildList {
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

// How long the just-added row holds its highlight, and how fast the card's
// color glides between resting and highlighted.
private const val ADDED_ROW_FLASH_HOLD_MILLIS = 650L
private const val ADDED_ROW_FLASH_FADE_MILLIS = 400
// Oldest "item added" event this screen will arm a reveal for. A real add is
// collected within milliseconds; anything older came out of the channel's
// buffer after a gap (the picker stays reachable from the low-stock view),
// and replaying it on return would scroll to a row added long ago.
private const val REVEAL_FRESHNESS_MILLIS = 3_000L
