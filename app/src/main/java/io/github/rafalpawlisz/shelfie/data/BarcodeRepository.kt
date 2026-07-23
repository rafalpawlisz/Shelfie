package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.model.ProductBarcode
import kotlinx.coroutines.flow.Flow

interface BarcodeRepository {

    fun observeBarcodes(): Flow<List<ProductBarcode>>

    suspend fun addBarcode(productId: String, barcode: String)

    suspend fun removeBarcode(barcode: String)
}
