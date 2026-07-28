package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    // Searchable too, or the archive is a place things vanish into: a name that
    // exists there looked like a name that did not exist at all, and the only
    // way onwards was a form that offered to "create" it.
    archivedProducts: List<Product>,
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
        ?: archivedProducts.firstOrNull { it.id == selectedProductId }
    val selectedIsArchived = archivedProducts.any { it.id == selectedProductId }

    // A product picked (or just created) but not yet in the lists Room hands
    // us. Treated as the amount step, not as "nothing picked": falling back to
    // the search meant a flash of it — keyboard, stolen focus and all — every
    // time the picker reopened around a freshly created product.
    val awaitingProduct = selectedProductId != null && selectedProduct == null

    // One meaning of "back" for every way of asking: the arrow in the bar, the
    // system gesture, the hardware button. From the amount step it returns to
    // the list; only the list itself closes the picker. Without this the
    // system back skipped the first step and threw away a chosen product.
    val goBack = {
        if (selectedProductId == null) onDismiss() else selectedProductId = null
    }

    Dialog(
        onDismissRequest = goBack,
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
                            IconButton(onClick = goBack) {
                                Icon(
                                    imageVector = if (selectedProductId == null) {
                                        Icons.Default.Clear
                                    } else {
                                        Icons.AutoMirrored.Filled.ArrowBack
                                    },
                                    contentDescription = stringResource(
                                        if (selectedProductId == null) {
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
                        // Nothing but the bar while the picked product is still
                        // on its way; back leaves for the search as usual.
                        if (!awaitingProduct) {
                            SearchPhase(
                                products = products,
                                archivedProducts = archivedProducts,
                                onSelect = { selectedProductId = it },
                                onCreateProduct = onCreateProduct,
                            )
                        }
                    } else {
                        Text(
                            text = listOfNotNull(selectedProduct.emoji, selectedProduct.name)
                                .joinToString(" "),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        // Adding it brings it back — said before confirming, not
                        // discovered afterwards on the Products tab.
                        if (selectedIsArchived) {
                            Text(
                                text = stringResource(R.string.picker_archived_returns),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
    archivedProducts: List<Product>,
    onSelect: (productId: String) -> Unit,
    onCreateProduct: (name: String) -> Unit,
) {
    // The search stays even with an empty pantry: typing a name and creating it
    // here is the shortest way out of "no products yet", better than a sign
    // pointing at another tab.
    var query by rememberSaveable { mutableStateOf("") }
    val visibleProducts = products.filterByName(query)
    // Archived matches only while searching: the archive is something to find
    // by name here, not to browse through on the way to the shopping list.
    val visibleArchived = if (query.isBlank()) emptyList() else archivedProducts.filterByName(query)

    // Focus and keyboard up front: this screen exists to be typed into, and
    // coming back from the amount step lands here to search again. Scoped to
    // this composable, so the Products tab's identical field stays quiet.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    ProductSearchField(
        query = query,
        onQueryChange = { query = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .focusRequester(searchFocus),
    )
    if (query.isBlank() && products.isEmpty()) {
        Text(
            text = stringResource(R.string.empty_state_go_to_products),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    // Offering to create is only honest when the name is unknown everywhere —
    // archive included, or the button proposes to make a second product with a
    // name the pantry already has.
    if (query.isNotBlank() && visibleProducts.isEmpty() && visibleArchived.isEmpty()) {
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
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(visibleProducts, key = { it.id }) { product ->
            ProductListItem(product = product, onClick = { onSelect(product.id) })
        }
        if (visibleArchived.isNotEmpty()) {
            item(key = "archived-header") {
                Text(
                    text = stringResource(R.string.picker_archived_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(visibleArchived, key = { it.id }) { product ->
                // Dimmed like on the Products tab, so a row from the archive
                // never passes for a product currently in the pantry.
                ProductListItem(
                    product = product,
                    dimmed = true,
                    onClick = { onSelect(product.id) },
                )
            }
        }
    }
}
