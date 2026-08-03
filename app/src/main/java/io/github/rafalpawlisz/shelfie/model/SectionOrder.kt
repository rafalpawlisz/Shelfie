package io.github.rafalpawlisz.shelfie.model

/**
 * The order in which a shopping list walks the store sections.
 *
 * Kept as one value per list rather than a position per section: it is one
 * decision ("this is how our Lidl is laid out"), and if two phones reorder at
 * the same time, last-write-wins should hand over somebody's whole order — a
 * merge of two halves would be an order nobody chose.
 *
 * Stored as comma-separated enum names. Names rather than the emoji keys used
 * elsewhere: this string is read by a human in the database far more often than
 * by the app, and it is immune to the encoding surprises of emoji.
 */
object SectionOrder {

    /**
     * The stored string as a full order. Empty or null means the declaration
     * order in [ProductCategory], which is roughly how a shop is walked.
     *
     * Unknown names are dropped and missing ones appended in declaration order,
     * so the result is always every section exactly once. That is what makes
     * adding a seventeenth section later a non-event: existing lists simply
     * find it at the end instead of losing it.
     */
    fun parse(stored: String?): List<ProductCategory> {
        val named = stored
            ?.split(',')
            ?.mapNotNull { name -> byName[name.trim()] }
            ?.distinct()
            .orEmpty()
        return named + ProductCategory.entries.filterNot { it in named }
    }

    /** null for the default order, so "not customised" stays absent in the database. */
    fun store(order: List<ProductCategory>): String? =
        if (order == ProductCategory.entries.toList()) null else order.joinToString(",") { it.name }

    private val byName = ProductCategory.entries.associateBy { it.name }
}

/**
 * Where a section falls in this order; anything without a section (one-offs a
 * dictionary cannot place, pre-section emoji, "no section") trails the lot.
 */
fun List<ProductCategory>.rankOf(section: ProductCategory?): Int {
    if (section == null) return size
    val index = indexOf(section)
    return if (index == -1) size else index
}
