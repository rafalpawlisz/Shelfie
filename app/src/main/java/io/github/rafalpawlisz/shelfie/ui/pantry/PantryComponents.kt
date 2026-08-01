package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.emoji.DecorationSuggester
import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.model.ProductCategory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.rafalpawlisz.shelfie.ui.theme.warning
import java.time.LocalDate

@Composable
internal fun ProductList(
    products: List<Product>,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 16.dp,
    itemContent: @Composable (Product) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(products, key = { it.id }) { product ->
            itemContent(product)
        }
    }
}

@Composable
internal fun ProductListItem(
    product: Product,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable () -> Unit = {},
) {
    val textColor =
        if (dimmed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // The decoration, not the product's section: the section
                    // is what the group header above already says, and
                    // repeating it on every row of the group says nothing.
                    text = listOfNotNull(decorationFor(product.name), product.name)
                        .joinToString(" "),
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor,
                )
                val baseQuantity = product.unit
                    ?.let { stringResource(R.string.quantity_with_unit, product.quantity, it) }
                    ?: product.quantity.toString()
                // Below the minimum: highlight the stock and show the threshold.
                val minQuantity = product.minQuantity
                val isLow = !dimmed && minQuantity != null && product.quantity < minQuantity
                Text(
                    text = if (isLow) {
                        stringResource(R.string.quantity_below_min, baseQuantity, minQuantity!!)
                    } else {
                        baseQuantity
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isLow) MaterialTheme.colorScheme.warning else textColor,
                )
                // Only when somebody wrote one down; most products have none.
                if (product.expiresOn != null) {
                    val status = expiryStatusOf(product.expiresOn, rememberToday())
                    Text(
                        text = stringResource(R.string.expires_on, product.expiresOn),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            dimmed -> textColor
                            status == ExpiryStatus.EXPIRED -> MaterialTheme.colorScheme.error
                            status == ExpiryStatus.SOON -> MaterialTheme.colorScheme.warning
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            trailingContent()
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth()) { content() }
    } else {
        Card(modifier = modifier.fillMaxWidth()) { content() }
    }
}

@Composable
internal fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(56.dp).padding(bottom = 8.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** The store section a product belongs to; null for none and for pre-section emoji. */
internal fun Product.section(): ProductCategory? = ProductCategory.fromEmoji(emoji)

/**
 * Today's date, re-read every time the app comes back to the foreground.
 *
 * A plain remember { LocalDate.now() } is captured on first composition and
 * kept for as long as the screen lives — which for a pantry left open on a
 * bedside table means "today" can be several days old, and a date that passed
 * on Tuesday still reads as fine on Friday. Resuming is the moment that
 * matters: nobody is watching the list at midnight, but everybody comes back to
 * it in the morning.
 */
@Composable
internal fun rememberToday(): LocalDate {
    val lifecycleOwner = LocalLifecycleOwner.current
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            today = LocalDate.now()
        }
    }
    return today
}

/**
 * The emoji drawn before a name, read from the name every time. Nothing stores
 * it, so it cannot disagree with the name it decorates, and a product the
 * dictionary has never heard of simply goes without one.
 */
@Composable
internal fun decorationFor(name: String): String? =
    remember(name) { DecorationSuggester.suggest(name) }

/**
 * Products in aisle order, grouped by section, with the sectionless group last.
 * Order within a group is left alone — the repository already sorted by name.
 */
internal fun List<Product>.groupedBySection(): List<Pair<ProductCategory?, List<Product>>> =
    groupBy { it.section() }
        .toList()
        .sortedBy { (section, _) -> section?.ordinal ?: ProductCategory.entries.size }

/** The header over a group of rows belonging to one store section. */
@Composable
internal fun SectionHeader(section: ProductCategory?, modifier: Modifier = Modifier) {
    GroupHeader(
        text = section
            ?.let { "${it.emoji}  ${stringResource(it.nameRes)}" }
            ?: stringResource(R.string.category_none),
        modifier = modifier,
    )
}

/**
 * A label over a group of rows. Separate from [SectionHeader] because the
 * shopping list ends with a group that is not a store section — the cart — and
 * it has to look like every other header, not merely similar.
 */
@Composable
internal fun GroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp),
    )
}
