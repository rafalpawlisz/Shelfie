package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.ui.theme.warning
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListChipsRow(
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
    // upstream order changes (after a move is persisted) — but never mid-drag,
    // or an emission from the household would rebuild the list under the
    // finger and snap the chip back. Keyed on the flag too, so the mirror
    // catches up the moment the gesture ends.
    var draggingChip by remember { mutableStateOf(false) }
    val orderedLists = remember { mutableStateListOf<ShoppingList>().apply { addAll(lists) } }
    LaunchedEffect(lists, draggingChip) {
        if (!draggingChip && orderedLists != lists) {
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
                    onDragStarted = {
                        draggingChip = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragStopped = {
                        val from = lists.indexOfFirst { it.id == list.id }
                        val to = orderedLists.indexOfFirst { it.id == list.id }
                        if (from != -1 && to != -1 && from != to) onMoveList(from, to)
                        draggingChip = false
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
private fun ListNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // TextFieldValue so a rename opens with the cursor after the current name.
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialName, selection = TextRange(initialName.length)))
    }
    val nameFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.list_name_label)) },
                singleLine = true,
                modifier = Modifier.focusRequester(nameFocus),
            )
            // The name is the dialog's only input — focus it right away.
            LaunchedEffect(Unit) { nameFocus.requestFocus() }
        },
        confirmButton = {
            TextButton(
                enabled = name.text.isNotBlank(),
                onClick = { onConfirm(name.text.trim()) },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

