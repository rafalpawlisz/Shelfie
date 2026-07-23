package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem

@Entity(
    tableName = "shopping_list_items",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // Unique: at most one list entry per product (adds merge instead).
    indices = [Index(value = ["productId"], unique = true)],
)
data class ShoppingListItemEntity(
    @PrimaryKey val id: String,
    val productId: String,
    // How many to buy; always > 0 (enforced by the dialog and merge logic).
    val amount: Int,
    // null = still to buy; non-null = in the cart (marked bought). The amount
    // is applied to the product's quantity only at checkout(), not when checked.
    val checkedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

// Flat read model for the list screen: item columns + joined product info.
data class ShoppingListItemRow(
    val id: String,
    val productId: String,
    val amount: Int,
    val checkedAt: Long?,
    val productName: String,
    val productEmoji: String?,
    val productUnit: String?,
)

fun ShoppingListItemRow.toDomain(): ShoppingListItem = ShoppingListItem(
    id = id,
    productId = productId,
    amount = amount,
    isChecked = checkedAt != null,
    productName = productName,
    productEmoji = productEmoji,
    productUnit = productUnit,
)
