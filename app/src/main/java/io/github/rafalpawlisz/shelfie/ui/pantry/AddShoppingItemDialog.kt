package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem

/**
 * Two-phase picker: choose an active product, then optionally the amount to
 * buy. For a new item the fields start empty (blank amount = the bare need —
 * it's asked for at check-off). Picking a product that is already on the list
 * pre-fills its current amount and note, and confirming REPLACES them.
 *
 * Full-screen like the product form: searching a pantry is browsing, and a card
 * capped at a few hundred dp fought both the list and the keyboard. It also
 * makes the trip into the product form (for a name that does not exist yet) a
 * continuation rather than a jump between two shapes of surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddShoppingItemDialog(
    products: List<Product>,
    items: List<ShoppingListItem>,
    onConfirm: (productId: String, amount: Int?, note: String?) -> Unit,
    // A name the pantry does not have yet: hands it to the product form, which
    // is the one place a product is created. The caller reopens this dialog
    // with the new product in [preselectProductId] once it exists.
    onCreateProduct: (name: String) -> Unit,
    onDismiss: () -> Unit,
    preselectProductId: String? = null,
) {
    var selectedProductId by rememberSaveable(preselectProductId) {
        mutableStateOf(preselectProductId)
    }
    val selectedProduct = products.firstOrNull { it.id == selectedProductId }

    Dialog(
        onDismissRequest = onDismiss,
        // Edge-to-edge so the window reports IME insets; imePadding() below then
        // keeps the content above the keyboard.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Same status-bar handling as the product form: an edge-to-edge dialog
        // draws under it, so its icons have to follow the theme.
        val view = LocalView.current
        val lightStatusBars = !isSystemInDarkTheme()
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = lightStatusBars
            }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            val existing = selectedProduct?.let { product ->
                items.firstOrNull { it.productId == product.id }
            }
            // Amount and note live here rather than in the phase below, so the
            // confirm action can sit in the top bar — reachable with the
            // keyboard up, like the product form's Save.
            var amountText by rememberSaveable(selectedProductId) {
                mutableStateOf(existing?.amount?.toString().orEmpty())
            }
            var noteText by rememberSaveable(selectedProductId) {
                mutableStateOf(existing?.note.orEmpty())
            }
            val amount = amountText.trim().toIntOrNull()
            val amountValid = amountText.isBlank() || (amount != null && amount > 0)

            Scaffold(
                modifier = Modifier.imePadding(),
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    // Back steps out of the amount phase; from
                                    // the list it closes.
                                    if (selectedProduct == null) onDismiss()
                                    else selectedProductId = null
                                },
                            ) {
                                Icon(
                                    imageVector = if (selectedProduct == null) {
                                        Icons.Default.Clear
                                    } else {
                                        Icons.AutoMirrored.Filled.ArrowBack
                                    },
                                    contentDescription = stringResource(
                                        if (selectedProduct == null) {
                                            R.string.action_close
                                        } else {
                                            R.string.action_back
                                        },
                                    ),
                                )
                            }
                        },
                        title = { Text(stringResource(R.string.add_to_shopping_list)) },
                        actions = {
                            if (selectedProduct != null) {
                                TextButton(
                                    enabled = amountValid,
                                    onClick = {
                                        onConfirm(
                                            selectedProduct.id,
                                            if (amountText.isBlank()) null else amount,
                                            noteText.trim().ifBlank { null },
                                        )
                                    },
                                ) {
                                    Text(
                                        stringResource(
                                            if (existing != null) {
                                                R.string.action_save
                                            } else {
                                                R.string.action_add
                                            },
                                        ),
                                    )
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selectedProduct == null) {
                        SearchPhase(
                            products = products,
                            onSelect = { selectedProductId = it },
                            onCreateProduct = onCreateProduct,
                        )
                    } else {
                        Text(
                            text = listOfNotNull(selectedProduct.emoji, selectedProduct.name)
                                .joinToString(" "),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
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
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPhase(
    products: List<Product>,
    onSelect: (productId: String) -> Unit,
    onCreateProduct: (name: String) -> Unit,
) {
    // The search stays even with an empty pantry: typing a name and creating it
    // here is the shortest way out of "no products yet", better than a sign
    // pointing at another tab.
    var query by rememberSaveable { mutableStateOf("") }
    val visibleProducts = products.filterByName(query)

    ProductSearchField(
        query = query,
        onQueryChange = { query = it },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
            onClick = { onCreateProduct(query.trim()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.add_product_named, query.trim()))
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        items(visibleProducts, key = { it.id }) { product ->
            ProductListItem(product = product, onClick = { onSelect(product.id) })
        }
    }
}
