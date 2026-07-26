package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.data.local.ProductBarcodeDao
import io.github.rafalpawlisz.shelfie.data.local.ProductBarcodeEntity
import io.github.rafalpawlisz.shelfie.data.local.toDomain
import io.github.rafalpawlisz.shelfie.data.sync.NoopSyncEngine
import io.github.rafalpawlisz.shelfie.data.sync.SyncClock
import io.github.rafalpawlisz.shelfie.data.sync.SyncCollection
import io.github.rafalpawlisz.shelfie.data.sync.SyncEngine
import io.github.rafalpawlisz.shelfie.model.ProductBarcode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineBarcodeRepository(
    private val dao: ProductBarcodeDao,
    private val sync: SyncEngine = NoopSyncEngine,
    private val clock: SyncClock = SyncClock { System.currentTimeMillis() },
) : BarcodeRepository {

    override fun observeBarcodes(): Flow<List<ProductBarcode>> =
        dao.observeAll().map { entities -> entities.map(ProductBarcodeEntity::toDomain) }

    override suspend fun addBarcode(productId: String, barcode: String) {
        val now = clock.now()
        dao.insert(
            ProductBarcodeEntity(
                barcode = barcode.trim(),
                productId = productId,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    override suspend fun removeBarcode(productId: String, barcode: String) {
        // Nothing removed means the code had already moved to another product;
        // reporting a deletion would then wipe that product's mapping remotely.
        if (dao.deleteFrom(productId, barcode) > 0) {
            sync.onDeleted(SyncCollection.BARCODES, listOf(barcode))
        }
    }

    override suspend fun findProductId(barcode: String): String? =
        dao.findProductId(barcode.trim())
}
