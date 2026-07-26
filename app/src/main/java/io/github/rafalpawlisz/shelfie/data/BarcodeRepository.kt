package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.model.ProductBarcode
import kotlinx.coroutines.flow.Flow

interface BarcodeRepository {

    fun observeBarcodes(): Flow<List<ProductBarcode>>

    suspend fun addBarcode(productId: String, barcode: String)

    /**
     * Unmap [barcode] from [productId]. Scoped on purpose: the code may have
     * been reassigned to another product meanwhile (scanning it there moves
     * it), and deleting by code alone would take that mapping with it.
     */
    suspend fun removeBarcode(productId: String, barcode: String)

    suspend fun findProductId(barcode: String): String?
}
