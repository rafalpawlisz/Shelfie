package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.rafalpawlisz.shelfie.model.ProductBarcode

@Entity(
    tableName = "product_barcodes",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["productId"])],
)
data class ProductBarcodeEntity(
    // The EAN/UPC is globally unique to one physical article, so it is the key.
    @PrimaryKey val barcode: String,
    val productId: String,
    val createdAt: Long,
)

fun ProductBarcodeEntity.toDomain(): ProductBarcode = ProductBarcode(
    productId = productId,
    barcode = barcode,
)
