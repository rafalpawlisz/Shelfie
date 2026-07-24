package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
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
import io.github.rafalpawlisz.shelfie.model.Product

/**
 * Two-phase picker: choose an active product, then optionally the amount to
 * buy. The field starts empty; leaving it blank records the bare need — the
 * actual amount is asked for when the item is checked off in the store.
 */
@Composable
fun AddShoppingItemDialog(
    products: List<Product>,
    onConfirm: (productId: String, amount: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedProductId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedProduct = products.firstOrNull { it.id == selectedProductId }

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
                if (selectedProduct == null) {
                    if (products.isEmpty()) {
                        Text(
                            text = stringResource(R.string.empty_state_go_to_products),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(products, key = { it.id }) { product ->
                                ProductListItem(
                                    product = product,
                                    onClick = { selectedProductId = product.id },
                                )
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                } else {
                    AmountPhase(
                        product = selectedProduct,
                        onConfirm = onConfirm,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun AmountPhase(
    product: Product,
    onConfirm: (productId: String, amount: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Always starts empty; blank = no amount ("just buy it").
    var amountText by rememberSaveable(product.id) { mutableStateOf("") }
    val amount = amountText.trim().toIntOrNull()
    val isValid = amountText.isBlank() || (amount != null && amount > 0)

    Text(
        text = listOfNotNull(product.emoji, product.name).joinToString(" "),
        style = MaterialTheme.typography.titleMedium,
    )
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
            onClick = { onConfirm(product.id, if (amountText.isBlank()) null else amount) },
        ) {
            Text(stringResource(R.string.action_add))
        }
    }
}
