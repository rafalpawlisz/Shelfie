package io.github.rafalpawlisz.shelfie.model

data class ShoppingListItem(
    val id: String,
    val productId: String,
    // null = "just buy it"; the actual amount is asked for at check-off.
    val amount: Int?,
    val isChecked: Boolean,
    val productName: String,
    val productEmoji: String?,
    val productUnit: String?,
    // Manual sort position within the list (persisted per list+product).
    val position: Double,
)
