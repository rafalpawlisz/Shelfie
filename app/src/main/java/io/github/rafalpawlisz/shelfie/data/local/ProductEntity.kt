package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.rafalpawlisz.shelfie.model.Product

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val quantity: Int,
    val unit: String?,
    // Bookkeeping for a future sync layer; never shown in the UI.
    val updatedAt: Long,
)

fun ProductEntity.toDomain(): Product = Product(
    id = id,
    name = name,
    quantity = quantity,
    unit = unit,
)
