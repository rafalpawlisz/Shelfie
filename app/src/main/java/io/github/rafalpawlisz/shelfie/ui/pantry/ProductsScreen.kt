package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.ui.theme.warning
import java.time.LocalDate

@Composable
fun ProductsScreen(
    products: List<Product>,
    archivedProducts: List<Product>,
    onProductClick: (String) -> Unit,
) {
    if (products.isEmpty() && archivedProducts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            EmptyState(
                title = stringResource(R.string.empty_state_title),
                message = stringResource(R.string.empty_state_message),
                modifier = Modifier.align(Alignment.Center),
                icon = Icons.AutoMirrored.Filled.List,
            )
        }
        return
    }

    var archivedExpanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // Presentation-only filter; the source lists stay sorted by the repository.
    val visibleProducts = products.filterByName(query)
    val visibleArchived = archivedProducts.filterByName(query)
    // Counted over the whole pantry, not over the search: the chip answers
    // "is anything running out of date", which has nothing to do with what is
    // typed in the box. Scoped to the search it shrank as you typed, and a
    // query matching nothing expiring emptied it — turning the filter off for
    // good through the guard below.
    val expiring = products.expiringFirst(rememberToday())
    // A filter, not a pinned block: the pantry keeps reading aisle by aisle,
    // and dates are looked at when you go looking for them. The chip is the
    // reminder that there is something to look at — it only exists when there
    // is.
    var expiringOnly by rememberSaveable { mutableStateOf(false) }
    // Using up or re-dating the last one leaves the filter showing nothing at
    // all, which reads as an empty pantry.
    LaunchedEffect(expiring.isEmpty()) {
        if (expiring.isEmpty()) expiringOnly = false
    }
    val shownProducts = if (expiringOnly) expiring.filterByName(query) else visibleProducts

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.products_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ProductSearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (expiring.isNotEmpty()) {
            FilterChip(
                selected = expiringOnly,
                onClick = { expiringOnly = !expiringOnly },
                label = {
                    Text(
                        text = stringResource(R.string.expiring_filter, expiring.size),
                        color = if (expiringOnly) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.warning
                        },
                    )
                },
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
        }
        // With the filter on, the archive is hidden anyway, so it cannot be the
        // reason the screen is not empty.
        val nothingMatches = shownProducts.isEmpty() && (expiringOnly || visibleArchived.isEmpty())
        if (query.isNotBlank() && nothingMatches) {
            Text(
                text = stringResource(R.string.search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Extra bottom padding keeps the FAB clear of the last row.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Grouped by store section while browsing, so the pantry reads in
            // the same order as the shopping list. While searching — or with
            // the expiry filter on — it stays flat: the answer is a handful of
            // rows in an order of their own, and aisle headers over them are
            // noise, not structure.
            if (query.isBlank() && !expiringOnly) {
                val grouped = shownProducts.groupedBySection()
                // A pantry where nothing has a section yet would get a single
                // "No section" label over everything, which explains nothing.
                val headers = grouped.size > 1 || grouped.singleOrNull()?.first != null
                grouped.forEach { (section, group) ->
                    if (headers) {
                        item(key = "section-${section?.name ?: "none"}") {
                            SectionHeader(section, modifier = Modifier.animateItem())
                        }
                    }
                    items(group, key = { it.id }) { product ->
                        ProductListItem(
                            product = product,
                            modifier = Modifier.animateItem(),
                            onClick = { onProductClick(product.id) },
                        )
                    }
                }
            } else {
                items(shownProducts, key = { it.id }) { product ->
                    ProductListItem(
                        product = product,
                        modifier = Modifier.animateItem(),
                        onClick = { onProductClick(product.id) },
                    )
                }
            }
            // The archive answers a different question than "what runs out
            // next", and an archived product is not going to be eaten.
            if (visibleArchived.isNotEmpty() && !expiringOnly) {
                item(key = "archived-header") {
                    TextButton(onClick = { archivedExpanded = !archivedExpanded }) {
                        Icon(
                            imageVector = if (archivedExpanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = null,
                        )
                        Text(
                            text = stringResource(
                                R.string.archived_section,
                                visibleArchived.size,
                            ),
                        )
                    }
                }
                if (archivedExpanded) {
                    items(visibleArchived, key = { it.id }) { product ->
                        ProductListItem(
                            product = product,
                            modifier = Modifier.animateItem(),
                            dimmed = true,
                            onClick = { onProductClick(product.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Case-insensitive name filter shared by the Products tab and the list picker. */
internal fun List<Product>.filterByName(query: String): List<Product> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return filter { it.name.contains(trimmed, ignoreCase = true) }
}

@Composable
internal fun ProductSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.search_clear),
                    )
                }
            }
        } else {
            null
        },
        singleLine = true,
        modifier = modifier,
    )
}
