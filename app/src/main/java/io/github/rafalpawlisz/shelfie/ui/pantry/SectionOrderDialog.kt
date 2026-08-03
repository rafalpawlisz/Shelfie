package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.ProductCategory
import io.github.rafalpawlisz.shelfie.model.SectionOrder
import io.github.rafalpawlisz.shelfie.ui.DragHandleIcon
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Drag the sections into the order this shop is walked in.
 *
 * Full-screen, like the product form: sixteen rows plus room to drag them is
 * not a decision that fits in a card, and dragging near the edge of a small
 * dialog is how you drop things by accident.
 *
 * All sixteen are listed, including the ones this list has nothing from today:
 * on the list itself a section only appears when it holds an item, so dragging
 * headers there could never reach the aisle you are not buying from right now —
 * which is exactly the aisle you want to place while you remember the shop.
 *
 * The order is applied on confirm, not per drag: a half-rearranged order is not
 * something to publish to the other phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SectionOrderDialog(
    listName: String,
    initialOrder: List<ProductCategory>,
    onConfirm: (List<ProductCategory>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Saveable across a configuration change: sixteen drags are too much work
    // to lose to a rotation. Enums ride the bundle as their names, through the
    // same forgiving parser the database column uses.
    val order = rememberSaveable(
        saver = listSaver(
            save = { it.map(ProductCategory::name) },
            restore = { names ->
                mutableStateListOf<ProductCategory>().apply {
                    addAll(SectionOrder.parse(names.joinToString(",")))
                }
            },
        ),
    ) {
        mutableStateListOf<ProductCategory>().apply { addAll(initialOrder) }
    }
    val lazyListState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        order.add(to.index, order.removeAt(from.index))
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Edge-to-edge, like the product form: a full-screen editor that stops
        // short of the system bars reads as a very tall card, not a screen.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // The dialog draws under the status bar; keep its icons legible for the
        // current theme (dark icons on light, light on dark).
        val view = LocalView.current
        val lightStatusBars = !isSystemInDarkTheme()
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = lightStatusBars
            }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.action_close),
                                )
                            }
                        },
                        title = {
                            Text(
                                text = stringResource(R.string.section_order_title, listName),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        actions = {
                            TextButton(onClick = { onConfirm(order.toList()) }) {
                                Text(stringResource(R.string.action_save))
                            }
                        },
                    )
                },
            ) { innerPadding ->
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
                                                haptic.performHapticFeedback(
                                                    HapticFeedbackType.LongPress,
                                                )
                                            },
                                        ),
                                        onClick = {},
                                    ) {
                                        Icon(
                                            imageVector = DragHandleIcon,
                                            contentDescription =
                                                stringResource(R.string.cd_drag_handle),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item(key = "default") {
                        // Back to the declaration order, which is what a list that
                        // was never customised uses — and the way out of a layout
                        // somebody dragged into nonsense. Below the sections, so a
                        // thumb reaching for a drag handle cannot find it.
                        TextButton(
                            onClick = { onConfirm(ProductCategory.entries.toList()) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.action_restore_default))
                        }
                    }
                }
            }
        }
    }
}
