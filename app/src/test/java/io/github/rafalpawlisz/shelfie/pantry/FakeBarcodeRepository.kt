package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.data.BarcodeRepository
import io.github.rafalpawlisz.shelfie.model.ProductBarcode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeBarcodeRepository : BarcodeRepository {

    private val barcodes = MutableStateFlow<List<ProductBarcode>>(emptyList())

    override fun observeBarcodes(): Flow<List<ProductBarcode>> = barcodes

    override suspend fun addBarcode(productId: String, barcode: String) {
        // Mirror the DAO's REPLACE-on-primary-key: a code maps to one product.
        barcodes.update { list ->
            list.filterNot { it.barcode == barcode } + ProductBarcode(productId, barcode)
        }
    }

    override suspend fun removeBarcode(barcode: String) {
        barcodes.update { list -> list.filterNot { it.barcode == barcode } }
    }

    override suspend fun findProductId(barcode: String): String? =
        barcodes.value.firstOrNull { it.barcode == barcode }?.productId
}
