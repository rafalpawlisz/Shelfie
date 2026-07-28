package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.model.ShoppingList

// Read-only view of the products below their minimum: tapping a row opens the
// restock dialog (store picker); "Add all" sends every shortage to one list.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LowStockView(
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
                    modifier = Modifier.animateItem(),
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

