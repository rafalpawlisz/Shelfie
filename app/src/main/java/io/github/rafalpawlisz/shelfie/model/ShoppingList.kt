package io.github.rafalpawlisz.shelfie.model

data class ShoppingList(
    val id: String,
    val name: String,
    // Manual sort position among lists (persisted per list).
    val position: Double,
)
