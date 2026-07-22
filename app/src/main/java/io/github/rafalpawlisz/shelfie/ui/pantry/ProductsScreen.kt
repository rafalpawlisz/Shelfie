package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.Product

@Composable
fun ProductsScreen(products: List<Product>, onDelete: (String) -> Unit) {
    if (products.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            EmptyState(
                title = stringResource(R.string.empty_state_title),
                message = stringResource(R.string.empty_state_message),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    } else {
        // Extra bottom padding keeps the FAB clear of the last row.
        ProductList(products = products, bottomPadding = 88.dp) { product ->
            ProductListItem(
                product = product,
                trailingContent = {
                    IconButton(onClick = { onDelete(product.id) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription =
                                stringResource(R.string.cd_delete_product, product.name),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
    }
}
