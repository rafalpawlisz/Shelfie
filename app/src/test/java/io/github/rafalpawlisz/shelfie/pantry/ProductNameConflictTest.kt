package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.ui.pantry.ProductNameConflict
import io.github.rafalpawlisz.shelfie.ui.pantry.productNameConflict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductNameConflictTest {

    private fun product(id: String, name: String) =
        Product(
            id = id,
            name = name,
            quantity = 0,
            unit = null,
            minQuantity = null,
            notes = null,
            emoji = null,
        )

    private val milk = product("p1", "Mleko")
    private val peas = product("p2", "Groszek")

    @Test
    fun `a free name has no conflict`() {
        assertNull(productNameConflict(listOf(milk), listOf(peas), "Kasza"))
    }

    @Test
    fun `case and spacing do not make a name free`() {
        // The repository matches this way too — SQLite's NOCASE folds ASCII
        // only, so the comparison has to happen in Kotlin.
        assertEquals(
            ProductNameConflict.ACTIVE,
            productNameConflict(listOf(milk), emptyList(), "  mLEko "),
        )
    }

    @Test
    fun `an archived name reports the archive, not a plain duplicate`() {
        assertEquals(
            ProductNameConflict.ARCHIVED,
            productNameConflict(listOf(milk), listOf(peas), "groszek"),
        )
    }

    @Test
    fun `the edited product does not conflict with itself`() {
        // Saving "Mleko" unchanged, or fixing its case, must stay allowed.
        assertNull(productNameConflict(listOf(milk), emptyList(), "Mleko", selfId = "p1"))
        assertNull(productNameConflict(listOf(milk), emptyList(), "mleko", selfId = "p1"))
    }

    @Test
    fun `a blank name is left to the required-field check`() {
        assertNull(productNameConflict(listOf(milk), emptyList(), "   "))
    }
}
