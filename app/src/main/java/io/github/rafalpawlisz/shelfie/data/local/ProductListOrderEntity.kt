package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Persistent manual sort position of a product within a shopping list.
 *
 * Kept in its own table (not on [ShoppingListItemEntity]) so the position
 * survives an item being bought or removed: re-adding the product later
 * restores its slot. One row per (listId, productId). [position] is a
 * fractional index — reordering sets it to the midpoint between neighbours,
 * so moving one item never renumbers the others.
 */
@Entity(
    tableName = "product_list_order",
    primaryKeys = ["listId", "productId"],
    foreignKeys = [
        ForeignKey(
            entity = ShoppingListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // The composite PK already covers listId (leftmost); the productId FK
    // needs its own index to avoid a full scan on cascade delete.
    indices = [Index(value = ["productId"])],
)
data class ProductListOrderEntity(
    val listId: String,
    val productId: String,
    val position: Double,
    val updatedAt: Long,
)
