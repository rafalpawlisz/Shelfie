package io.github.rafalpawlisz.shelfie.model

/** A product planned on an active shopping list (one row per list × product). */
data class PlannedEntry(
    val listId: String,
    val productId: String,
)
