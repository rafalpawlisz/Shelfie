package io.github.rafalpawlisz.shelfie.model

data class ShoppingListItem(
    val id: String,
    // null = a one-off item: bought once, never part of the pantry. It has no
    // stock to bank at checkout and no manual sort slot.
    val productId: String?,
    // null = "just buy it"; the actual amount is asked for at check-off.
    val amount: Int?,
    // One-off shopping note; dies with the item at checkout/removal.
    val note: String?,
    val isChecked: Boolean,
    val productName: String,
    val productEmoji: String?,
    val productUnit: String?,
    // Manual sort position within the list (persisted per list+product).
    val position: Double,
)
