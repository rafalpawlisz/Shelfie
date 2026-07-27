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
import androidx.compose.material3.Button
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
import io.github.rafalpawlisz.shelfie.emoji.EmojiSuggester
import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem

/**
 * Two-phase picker: choose an active product, then optionally the amount to
 * buy. For a new item the fields start empty (blank amount = the bare need —
 * it's asked for at check-off). Picking a product that is already on the list
 * pre-fills its current amount and note, and confirming REPLACES them.
 */
@Composable
fun AddShoppingItemDialog(
    products: List<Product>,
    items: List<ShoppingListItem>,
    onConfirm: (productId: String, amount: Int?, note: String?) -> Unit,
    // A name the pantry does not have yet: create the product and list it in
    // one go. This is where the gap gets noticed, so this is where it is fixed.
    onCreateAndConfirm: (name: String, amount: Int?, note: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedProductId by rememberSaveable { mutableStateOf<String?>(null) }
    var newProductName by rememberSaveable { mutableStateOf<String?>(null) }
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
                val pendingName = newProductName
                if (selectedProduct == null && pendingName == null) {
                    // The search stays even with an empty pantry: typing a name
                    // and creating it here is the shortest way out of "no
                    // products yet", better than a sign pointing at another tab.
                    var query by rememberSaveable { mutableStateOf("") }
                    val visibleProducts = products.filterByName(query)
                    ProductSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (query.isBlank() && products.isEmpty()) {
                        Text(
                            text = stringResource(R.string.empty_state_go_to_products),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (query.isNotBlank() && visibleProducts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { newProductName = query.trim() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.add_product_named, query.trim()))
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visibleProducts, key = { it.id }) { product ->
                            ProductListItem(
                                product = product,
                                onClick = { selectedProductId = product.id },
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                } else if (selectedProduct != null) {
                    AmountPhase(
                        title = listOfNotNull(selectedProduct.emoji, selectedProduct.name)
                            .joinToString(" "),
                        resetKey = selectedProduct.id,
                        existing = items.firstOrNull { it.productId == selectedProduct.id },
                        onConfirm = { amount, note ->
                            onConfirm(selectedProduct.id, amount, note)
                        },
                        onDismiss = onDismiss,
                    )
                } else if (pendingName != null) {
                    AmountPhase(
                        // The emoji the product is about to get, shown before it
                        // exists, so the guess is visible while it can be undone
                        // by simply going back.
                        title = listOfNotNull(EmojiSuggester.suggest(pendingName), pendingName)
                            .joinToString(" "),
                        resetKey = pendingName,
                        existing = null,
                        onConfirm = { amount, note ->
                            onCreateAndConfirm(pendingName, amount, note)
                        },
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun AmountPhase(
    title: String,
    // Identifies what is being added, so the fields reset when it changes.
    resetKey: Any,
    existing: ShoppingListItem?,
    onConfirm: (amount: Int?, note: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // New items start empty (blank = no amount, "just buy it"); an already-listed
    // product shows its current values, so confirming replaces them knowingly.
    var amountText by rememberSaveable(resetKey) {
        mutableStateOf(existing?.amount?.toString().orEmpty())
    }
    // One-off shopping note; dies with the item at checkout.
    var noteText by rememberSaveable(resetKey) { mutableStateOf(existing?.note.orEmpty()) }
    val amount = amountText.trim().toIntOrNull()
    val isValid = amountText.isBlank() || (amount != null && amount > 0)

    Text(text = title, style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = amountText,
        onValueChange = { amountText = it },
        label = { Text(stringResource(R.string.shopping_amount_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = noteText,
        onValueChange = { noteText = it },
        label = { Text(stringResource(R.string.product_notes_label)) },
        maxLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.action_cancel))
        }
        TextButton(
            enabled = isValid,
            onClick = {
                onConfirm(
                    if (amountText.isBlank()) null else amount,
                    noteText.trim().ifBlank { null },
                )
            },
        ) {
            Text(
                stringResource(
                    if (existing != null) R.string.action_save else R.string.action_add,
                ),
            )
        }
    }
}
