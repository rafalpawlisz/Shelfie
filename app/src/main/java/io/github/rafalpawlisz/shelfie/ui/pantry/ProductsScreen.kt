package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.Product

@Composable
fun ProductsScreen(products: List<Product>, onProductClick: (String) -> Unit) {
    if (products.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            EmptyState(
                title = stringResource(R.string.empty_state_title),
                message = stringResource(R.string.empty_state_message),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.products_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // Extra bottom padding keeps the FAB clear of the last row.
            ProductList(products = products, bottomPadding = 88.dp) { product ->
                ProductListItem(
                    product = product,
                    onClick = { onProductClick(product.id) },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}
