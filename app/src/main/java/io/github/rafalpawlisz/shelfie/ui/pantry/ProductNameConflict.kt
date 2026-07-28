package io.github.rafalpawlisz.shelfie.ui.pantry

import io.github.rafalpawlisz.shelfie.model.Product

/**
 * Why a typed product name cannot be used as it stands. Two products of one
 * name split what should be one thing: a scanned barcode reaches only one of
 * them, the low-stock list counts them apart, and the other phone sees a pair.
 */
enum class ProductNameConflict {
    /** Another product in the pantry already carries this name. */
    ACTIVE,

    /** The name belongs to an archived product, which can be restored instead. */
    ARCHIVED,
}

/**
 * Match names the way the repository's `findByName` does — trimmed and
 * case-insensitive in Kotlin, since SQLite's NOCASE only folds ASCII and
 * "Jabłko" would slip past it. [selfId] is the product being edited, which
 * must not conflict with itself.
 */
fun productNameConflict(
    active: List<Product>,
    archived: List<Product>,
    typed: String,
    selfId: String? = null,
): ProductNameConflict? {
    val name = typed.trim()
    if (name.isEmpty()) return null
    fun List<Product>.holdsTheName() =
        any { it.id != selfId && it.name.trim().equals(name, ignoreCase = true) }
    return when {
        active.holdsTheName() -> ProductNameConflict.ACTIVE
        archived.holdsTheName() -> ProductNameConflict.ARCHIVED
        else -> null
    }
}
