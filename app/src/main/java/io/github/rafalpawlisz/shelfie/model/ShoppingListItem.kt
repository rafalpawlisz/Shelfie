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
    // The section this row shows and sorts by, already resolved: the product's,
    // or a one-off's own answer, or what its name implies.
    val productEmoji: String?,
    // What was actually stored on a one-off, before that resolution — null when
    // nobody has picked a section for it. The edit dialog needs the difference:
    // showing the resolved value but sending it back unchanged would quietly
    // freeze a guess into an answer.
    val sectionEmoji: String?,
    val productUnit: String?,
    // Manual sort position within the list (per list+product for a product, on
    // the row itself for a one-off).
    val position: Double,
)
