package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.ShoppingList

/**
 * Follow-up of the low-stock snackbar action: pick the store list (last-used
 * one preselected via [defaultListId]) and the amount (prefilled with the
 * top-up-to-minimum suggestion), then add the product to that list.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RestockDialog(
    productLabel: String,
    lists: List<ShoppingList>,
    defaultListId: String?,
    suggestedAmount: Int,
    onConfirm: (listId: String, amount: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedListId by rememberSaveable {
        mutableStateOf(defaultListId ?: lists.firstOrNull()?.id)
    }
    var amountText by rememberSaveable { mutableStateOf(suggestedAmount.toString()) }
    val amount = amountText.toIntOrNull()
    val isValid = amount != null && amount > 0 && lists.any { it.id == selectedListId }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.add_to_shopping_list),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    text = productLabel,
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lists.forEach { list ->
                        FilterChip(
                            selected = list.id == selectedListId,
                            onClick = { selectedListId = list.id },
                            label = { Text(list.name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(stringResource(R.string.shopping_amount_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        enabled = isValid,
                        onClick = { onConfirm(selectedListId!!, amount ?: 1) },
                    ) {
                        Text(stringResource(R.string.action_add))
                    }
                }
            }
        }
    }
}
