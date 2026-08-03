package io.github.rafalpawlisz.shelfie.model

data class ShoppingList(
    val id: String,
    val name: String,
    // Manual sort position among lists (persisted per list).
    val position: Double,
    // The aisle order this shop is walked in — always complete, defaulting to
    // ProductCategory's declaration order. See [SectionOrder].
    val sectionOrder: List<ProductCategory> = ProductCategory.entries.toList(),
)
