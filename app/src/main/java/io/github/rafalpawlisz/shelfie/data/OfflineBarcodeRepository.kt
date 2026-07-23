package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.data.local.ProductBarcodeDao
import io.github.rafalpawlisz.shelfie.data.local.ProductBarcodeEntity
import io.github.rafalpawlisz.shelfie.data.local.toDomain
import io.github.rafalpawlisz.shelfie.model.ProductBarcode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineBarcodeRepository(private val dao: ProductBarcodeDao) : BarcodeRepository {

    override fun observeBarcodes(): Flow<List<ProductBarcode>> =
        dao.observeAll().map { entities -> entities.map(ProductBarcodeEntity::toDomain) }

    override suspend fun addBarcode(productId: String, barcode: String) {
        dao.insert(
            ProductBarcodeEntity(
                barcode = barcode.trim(),
                productId = productId,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun removeBarcode(barcode: String) {
        dao.delete(barcode)
    }
}
