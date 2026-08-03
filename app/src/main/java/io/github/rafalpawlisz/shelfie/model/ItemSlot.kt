package io.github.rafalpawlisz.shelfie.model

/**
 * One row's manual slot within its aisle, as a reorder wants to persist it.
 *
 * Both identifiers travel because the two kinds of row keep their slot in
 * different places: a product's in product_list_order (keyed by list + product,
 * so it outlives the row and a re-added product returns to its place), a
 * one-off's on the row itself (it has no product to key by, and dies at
 * checkout). [productId] being null is what says which.
 */
data class ItemSlot(
    val itemId: String,
    val productId: String?,
    val position: Double,
)
