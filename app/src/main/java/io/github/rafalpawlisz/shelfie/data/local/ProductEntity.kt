package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.ColumnInfo
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
    // Soft delete: null = active. Kept as a tombstone for future sync.
    val archivedAt: Long? = null,
    // Kept from the pre-release history, when this column arrived by ALTER
    // TABLE; harmless now that v1 is the baseline, and removing it would be a
    // schema change for nothing.
    @ColumnInfo(defaultValue = "0") val createdAt: Long,
    // Restock threshold; null = feature off for this product.
    val minQuantity: Int? = null,
    val notes: String? = null,
    // Visual marker shown before the name on lists.
    val emoji: String? = null,
    /**
     * Best-before date as "yyyy-MM-dd", or null when nobody wrote one down.
     *
     * A date and nothing more: the printed date on a package has no time and no
     * timezone, and storing an instant would invent both — then shift the day
     * across a border or a DST edge. In this form it also sorts and compares as
     * plain text, chronologically, in SQLite and in Firestore alike.
     *
     * It describes what is in the cupboard, not a batch: this is for the rarely
     * bought things at the back, where the count is one and the date is written
     * once. Nothing keeps it true by itself — buying a second jar means editing
     * it, the same as writing it.
     */
    val expiresOn: String? = null,
)

fun ProductEntity.toDomain(): Product = Product(
    id = id,
    name = name,
    quantity = quantity,
    unit = unit,
    minQuantity = minQuantity,
    notes = notes,
    emoji = emoji,
    expiresOn = expiresOn,
)
