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
    // Manual sort position within the list (per list+product for a product, on
    // the row itself for a one-off).
    val position: Double,
    // False when [position] is only a fallback (a one-off nobody has dragged
    // yet, sorted by creation time). Such a row must not lend its position to a
    // neighbour: a timestamp in millis is not a slot.
    val hasManualPosition: Boolean = true,
)
