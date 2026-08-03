package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shopping_lists")
data class ShoppingListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    // Manual sort order among lists (fractional index); new lists append at the end.
    val position: Double,
    // Soft delete: null = active. Archiving keeps the row (and its items + order)
    // so the list can be restored intact; only a permanent delete drops it.
    val archivedAt: Long? = null,
    // The aisle order for this shop, comma-separated section names; null = the
    // default. One value, not a row per section — see [SectionOrder].
    val sectionOrder: String? = null,
)
