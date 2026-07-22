package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import io.github.rafalpawlisz.shelfie.ui.RemoveIcon

@Composable
fun UseUpScreen(products: List<Product>, onDecrement: (String) -> Unit) {
    if (products.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            EmptyState(
                title = stringResource(R.string.empty_state_title),
                message = stringResource(R.string.empty_state_go_to_products),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.use_up_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ProductList(products = products) { product ->
                ProductListItem(
                    product = product,
                    enabled = product.quantity > 0,
                    onClick = { onDecrement(product.id) },
                    trailingContent = {
                        Icon(
                            imageVector = RemoveIcon,
                            contentDescription =
                                stringResource(R.string.cd_decrease_quantity, product.name),
                        )
                    },
                )
            }
        }
    }
}
