package io.github.rafalpawlisz.shelfie.model

data class ShoppingListItem(
    val id: String,
    val productId: String,
    val amount: Int,
    val isChecked: Boolean,
    val productName: String,
    val productEmoji: String?,
    val productUnit: String?,
    // Manual sort position within the list (persisted per list+product).
    val position: Double,
)
