package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.ProductCategory
import io.github.rafalpawlisz.shelfie.ui.DragHandleIcon
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Drag the sections into the order this shop is walked in.
 *
 * All sixteen are listed, including the ones this list has nothing from today:
 * on the list itself a section only appears when it holds an item, so dragging
 * headers there could never reach the aisle you are not buying from right now —
 * which is exactly the aisle you want to place while you remember the shop.
 *
 * The order is applied on confirm, not per drag: a half-rearranged order is not
 * something to publish to the other phone.
 */
@Composable
internal fun SectionOrderDialog(
    listName: String,
    initialOrder: List<ProductCategory>,
    onConfirm: (List<ProductCategory>) -> Unit,
    onDismiss: () -> Unit,
) {
    val order = remember { mutableStateListOf<ProductCategory>().apply { addAll(initialOrder) } }
    val lazyListState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        order.add(to.index, order.removeAt(from.index))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.section_order_title, listName)) },
        text = {
            LazyColumn(
                state = lazyListState,
                // Bounded so the dialog keeps its buttons on screen; sixteen
                // rows never fit on a phone.
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(count = order.size, key = { order[it].name }) { index ->
                    val section = order[index]
                    ReorderableItem(reorderableState, key = section.name) { _ ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${section.emoji}  ${stringResource(section.nameRes)}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    modifier = Modifier.draggableHandle(
                                        onDragStarted = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                    ),
                                    onClick = {},
                                ) {
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
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(order.toList()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                // Back to the declaration order, which is what a list that was
                // never customised uses — and the way out of a layout somebody
                // dragged into nonsense.
                TextButton(onClick = { onConfirm(ProductCategory.entries.toList()) }) {
                    Text(stringResource(R.string.action_restore_default))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}
