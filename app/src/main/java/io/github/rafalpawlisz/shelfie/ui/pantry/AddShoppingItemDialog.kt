package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.OneOffSuggestion
import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem

/**
 * Two-phase picker: choose an active product, then optionally the amount to
 * buy. For a new item the fields start empty (blank amount = the bare need —
 * it's asked for at check-off). Picking a product that is already on the list
 * pre-fills its current amount and note, and confirming REPLACES them.
 *
 * The pantry list shows only products that are NOT on the chosen list yet;
 * the ones that already are gather under their own header at the bottom,
 * where tapping one still pre-fills its current values and REPLACES them.
 *
 * A one-off needs no second phase: it joins the list in one tap — bare, or
 * with an amount and unit typed into the search ("marchew 200 g"). Note and
 * section stay empty and are edited on the list if wanted.
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
    // Names bought once before, newest first; see OneOffSuggestion.
    suggestions: List<OneOffSuggestion>,
    onConfirm: (productId: String, amount: Int?, note: String?) -> Unit,
    // A one-off: a line of text on the list, never a product. Added in one tap
    // from the search. Amount and unit ride along when the text holds them
    // ("marchew 200 g"), or the unit the history remembers fills in alone;
    // note and section stay empty and are edited on the list.
    onConfirmOneOff: (
        name: String,
        amount: Int?,
        unit: String?,
        note: String?,
        sectionEmoji: String?,
    ) -> Unit,
    // A name the pantry does not have yet: hands it to the product form, which
    // is the one place a product is created. The caller reopens this dialog
    // with the new product in [preselectProductId] once it exists.
    onCreateProduct: (name: String) -> Unit,
    /** Drops a remembered name — a typo, or something never to be bought again. */
    onForgetSuggestion: (name: String) -> Unit,
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
    val inAmountStep = selectedProductId != null

    // The amount step scrolls like the product form — the fields can outgrow
    // what the keyboard leaves of the screen. Keyed to the step's owner so one
    // item's scroll offset is not the next one's; applied only in the amount
    // step, because the search phase is a LazyColumn, which must never sit
    // inside an outer vertical scroll.
    val amountStepScroll = remember(selectedProductId) { ScrollState(0) }

    // One meaning of "back" for every way of asking: the arrow in the bar, the
    // system gesture, the hardware button. From the amount step it returns to
    // the list; only the list itself closes the picker. Without this the
    // system back skipped the first step and threw away a chosen product.
    val goBack = {
        if (!inAmountStep) {
            onDismiss()
        } else {
            selectedProductId = null
        }
    }

    // Products already planned on the chosen list: the amount step pre-fills
    // from their row, and the search phase groups them under their own header
    // instead of offering them a second time among the pantry products.
    val onListProductIds = items.mapNotNull { it.productId }.toSet()

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
            // keyboard up, like the product form's Save. Keyed on the step's
            // owner so one item's draft is not the next one's.
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
                                    imageVector = if (!inAmountStep) {
                                        Icons.Default.Clear
                                    } else {
                                        Icons.AutoMirrored.Filled.ArrowBack
                                    },
                                    contentDescription = stringResource(
                                        if (!inAmountStep) {
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
                                        val cleanAmount = if (amountText.isBlank()) null else amount
                                        val cleanNote = noteText.trim().ifBlank { null }
                                        onConfirm(selectedProduct.id, cleanAmount, cleanNote)
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
                        .padding(horizontal = 24.dp)
                        .then(
                            if (inAmountStep) {
                                Modifier.verticalScroll(amountStepScroll)
                            } else {
                                Modifier
                            },
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selectedProduct == null) {
                        // Nothing but the bar while the picked product is still
                        // on its way; back leaves for the search as usual.
                        if (!awaitingProduct) {
                            SearchPhase(
                                products = products,
                                archivedProducts = archivedProducts,
                                onListProductIds = onListProductIds,
                                suggestions = suggestions,
                                onSelect = { selectedProductId = it },
                                onCreateProduct = onCreateProduct,
                                // A one-off joins the list in the same tap.
                                // A quantity typed into the search ("marchew
                                // 200 g") rides along as amount and unit; the
                                // note and section are still edited on the
                                // list, where the line already is.
                                onAddOneOff = { typed ->
                                    val split = splitOneOffQuery(typed)
                                    onConfirmOneOff(
                                        split.name,
                                        split.amount,
                                        // What the user typed is the measure;
                                        // the remembered unit only steps in
                                        // when they did not type one.
                                        split.unit ?: rememberedUnitFor(split.name, suggestions),
                                        null,
                                        null,
                                    )
                                },
                                onForgetSuggestion = onForgetSuggestion,
                            )
                        }
                    } else {
                        Text(
                            text = listOfNotNull(
                                decorationFor(selectedProduct.name),
                                selectedProduct.name,
                            ).joinToString(" "),
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
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                            ),
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
    // Products that already sit on the chosen list; they leave the pantry
    // list and gather at the bottom under their own header.
    onListProductIds: Set<String>,
    suggestions: List<OneOffSuggestion>,
    onSelect: (productId: String) -> Unit,
    onCreateProduct: (name: String) -> Unit,
    onAddOneOff: (name: String) -> Unit,
    onForgetSuggestion: (name: String) -> Unit,
) {
    // The search stays even with an empty pantry: typing a name and creating it
    // here is the shortest way out of "no products yet", better than a sign
    // pointing at another tab.
    var query by rememberSaveable { mutableStateOf("") }
    val visibleProducts = products.filterByName(query)
    // Products already on the chosen list answer in the bottom section, never
    // here — one place per product, so an add cannot look like a duplicate.
    val onThisList = visibleProducts.filter { it.id in onListProductIds }
    val notOnThisList = visibleProducts.filterNot { it.id in onListProductIds }
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
    // The other answer to a name the pantry does not have: things bought once
    // (a bulb, a grave candle) that have no business living among the products.
    //
    // Gated on an EXACT match, not on the search being empty: the search
    // matches substrings, so "mleko" finds "Mleko owsiane" and the one-off
    // route used to vanish for any name that merely occurs inside a product's.
    // Repeats are the one-off's whole point, so only the product itself — the
    // better answer — hides the offer. It rides at the end of the list rather
    // than above it: an escape hatch belongs after the results, not in front
    // of them.
    val exactMatch = (visibleProducts + visibleArchived).any {
        it.name.trim().equals(query.trim(), ignoreCase = true)
    }
    val offerOneOff = query.isNotBlank() && !exactMatch
    // Things bought once before. Filtered like the products; with an empty
    // search only the head of the list, because this is a memory aid, not an
    // inventory — a hundred old words would bury the pantry below them.
    val visibleSuggestions = suggestions
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        // A name that has since become a product belongs to the products: the
        // pantry is the better answer, and offering both would be offering a
        // choice with no meaning behind it.
        .filterNot { suggestion ->
            (products + archivedProducts).any { it.name.trim().equals(suggestion.name, true) }
        }
        .take(if (query.isBlank()) EMPTY_QUERY_SUGGESTIONS else Int.MAX_VALUE)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(notOnThisList, key = { it.id }) { product ->
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
        // Below the products, above the escape hatch: a thing bought before is
        // a better guess than a name typed from scratch, and a worse one than
        // something the pantry actually keeps.
        if (visibleSuggestions.isNotEmpty()) {
            item(key = "one-off-header") {
                Text(
                    text = stringResource(R.string.picker_one_off_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(visibleSuggestions, key = { "one-off-${it.name}" }) { suggestion ->
                OneOffSuggestionRow(
                    suggestion = suggestion,
                    onClick = { onAddOneOff(suggestion.name) },
                    onForget = { onForgetSuggestion(suggestion.name) },
                )
            }
        }
        if (offerOneOff) {
            item(key = "one-off") {
                OutlinedButton(
                    onClick = { onAddOneOff(query.trim()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.picker_one_off, query.trim()))
                }
            }
        }
        // Last, on purpose: it is not the answer most of the time — the pantry
        // rows above are. Something already on the list is here to be tuned
        // (amount up, note changed), not to be added again, and picking one
        // still lands on the pre-filled amount step.
        if (onThisList.isNotEmpty()) {
            item(key = "on-this-list-header") {
                Text(
                    text = stringResource(R.string.picker_on_this_list_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(onThisList, key = { it.id }) { product ->
                ProductListItem(product = product, onClick = { onSelect(product.id) })
            }
        }
    }
}

/**
 * A name bought once before. Deliberately plainer than a product row — no
 * quantity, no section emoji — because choosing it does not touch the pantry:
 * it writes another line that will leave the list at checkout, exactly as the
 * first one did.
 */
@Composable
private fun OneOffSuggestionRow(
    suggestion: OneOffSuggestion,
    onClick: () -> Unit,
    onForget: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(
                    // Read out of the name, like the row this will become on the
                    // list — a one-off has no stored emoji, and the dictionary
                    // answers the same way in both places.
                    text = listOfNotNull(decorationFor(suggestion.name), suggestion.name)
                        .joinToString(" "),
                    style = MaterialTheme.typography.bodyLarge,
                )
                // The unit comes back with the name because retyping "g" every
                // time is the tedious half; the amount does not, because how
                // many you wanted was true of one trip.
                if (suggestion.unit != null) {
                    Text(
                        text = suggestion.unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onForget) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(
                        R.string.picker_one_off_forget,
                        suggestion.name,
                    ),
                )
            }
        }
    }
}

// With no search typed, how many remembered names to show before the list
// stops being a hint and starts being a second pantry.
private const val EMPTY_QUERY_SUGGESTIONS = 8

/**
 * The unit this name was last bought with, or null if the history has never
 * seen it.
 *
 * A function of the name rather than a decision made when a suggestion was
 * tapped: the picker offers two routes to the same one-tap addition — a
 * remembered name and a freshly typed one — and a unit carried along the first
 * route used to survive into the second. Matching is loose in the same way the
 * history's own identity is, so typing a name it already knows brings its unit
 * too, which is the same answer picking it would have given.
 */
internal fun rememberedUnitFor(name: String?, suggestions: List<OneOffSuggestion>): String? {
    val wanted = name?.trim() ?: return null
    return suggestions.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }?.unit
}

/** Name, amount and unit a one-off query splits into; name alone when nothing split off. */
internal data class OneOffSplit(val name: String, val amount: Int?, val unit: String?)

// "marchew 200 g", "marchew 200g", "marchew 200". Letters only for the unit
// (g, kg, szt…), so "3,2%" or "00" never read as a quantity, and the name must
// not be empty — a bare "200" is a name, not an amount of nothing.
private val ONE_OFF_AMOUNT = Regex("^(.+?)\\s+(\\d+)\\s*(\\p{L}+)?$")

/**
 * Splits a one-off typed in one go — "marchew 200 g" — so the quantity lands on
 * the row instead of hiding in the name. The unit is what the amount counts;
 * "g" here, nothing when the amount was typed bare.
 */
internal fun splitOneOffQuery(query: String): OneOffSplit {
    val trimmed = query.trim()
    val match = ONE_OFF_AMOUNT.matchEntire(trimmed)
    if (match == null) return OneOffSplit(trimmed, null, null)
    val amount = match.groupValues[2].toIntOrNull()
    if (amount == null || amount <= 0) return OneOffSplit(trimmed, null, null)
    return OneOffSplit(
        name = match.groupValues[1].trim(),
        amount = amount,
        unit = match.groupValues[3].takeIf { it.isNotBlank() },
    )
}
