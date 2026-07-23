package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem

@Composable
fun ShoppingScreen(
    items: List<ShoppingListItem>,
    onToggle: (id: String, checked: Boolean) -> Unit,
    onRemove: (id: String) -> Unit,
    onClearPurchased: () -> Unit,
) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            EmptyState(
                title = stringResource(R.string.shopping_empty_title),
                message = stringResource(R.string.shopping_empty_message),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }

    val checkedCount = items.count { it.isChecked }

    Column(modifier = Modifier.fillMaxSize()) {
        // Hint and the (conditional) clear action share one fixed-height row so
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
                TextButton(onClick = onClearPurchased) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null)
                    Text(text = stringResource(R.string.clear_purchased, checkedCount))
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Extra bottom padding keeps the FAB clear of the last row.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { item ->
                ShoppingListRow(
                    item = item,
                    onToggle = { onToggle(item.id, !item.isChecked) },
                    onRemove = { onRemove(item.id) },
                )
            }
        }
    }
}

@Composable
private fun ShoppingListRow(
    item: ShoppingListItem,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val textColor =
        if (item.isChecked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
    val decoration = if (item.isChecked) TextDecoration.LineThrough else null

    Card(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Visual indicator only; the whole row is the touch target.
            Checkbox(checked = item.isChecked, onCheckedChange = null)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    text = listOfNotNull(item.productEmoji, item.productName).joinToString(" "),
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor,
                    textDecoration = decoration,
                )
                Text(
                    text = item.productUnit
                        ?.let { stringResource(R.string.shopping_amount_with_unit, item.amount, it) }
                        ?: stringResource(R.string.shopping_amount, item.amount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    textDecoration = decoration,
                )
            }
            if (!item.isChecked) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription =
                            stringResource(R.string.cd_remove_from_list, item.productName),
                    )
                }
            }
        }
    }
}
