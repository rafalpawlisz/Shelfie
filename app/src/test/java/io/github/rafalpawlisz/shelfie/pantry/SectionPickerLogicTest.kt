package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.model.ProductCategory
import io.github.rafalpawlisz.shelfie.ui.pantry.shownSectionEmoji
import io.github.rafalpawlisz.shelfie.ui.pantry.storedSectionEmoji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The section picker's two answers — what the field shows and what a save
 * writes back. They are two functions because they are allowed to disagree,
 * and the disagreement is the design: a suggestion on display is not an answer
 * on record.
 */
class SectionPickerLogicTest {

    @Test
    fun `an untouched save writes back exactly what was stored`() {
        // The freeze guard. A regression here is silent and creeping: every
        // edit of an amount would pin that day's guess to the row, and lines
        // would quietly stop following the dictionary as it improves.
        assertNull(storedSectionEmoji(touched = false, picked = "", stored = null))
        assertEquals("", storedSectionEmoji(touched = false, picked = "🍝", stored = ""))
        assertEquals("🍝", storedSectionEmoji(touched = false, picked = "", stored = "🍝"))
    }

    @Test
    fun `the shown suggestion and the stored answer are allowed to disagree`() {
        // The pair the whole design hangs on, held in one place: the field
        // SHOWS the dictionary's guess, and the save still writes nothing.
        val shown = shownSectionEmoji(
            touched = false,
            picked = "",
            stored = null,
            suggested = ProductCategory.DRINKS,
        )
        assertEquals(ProductCategory.DRINKS.emoji, shown)
        assertNull(storedSectionEmoji(touched = false, picked = "", stored = null))
    }

    @Test
    fun `a pick is stored, including the pick of no section`() {
        assertEquals("🍝", storedSectionEmoji(touched = true, picked = "🍝", stored = null))
        // "Bez działu" chosen over a previously picked section: "" goes down,
        // not null — saying "nowhere" must not reopen the guessing.
        assertEquals("", storedSectionEmoji(touched = true, picked = "", stored = "🍝"))
    }

    @Test
    fun `display prefers the pick, then the stored answer, then the dictionary`() {
        assertEquals("🏠", shownSectionEmoji(true, "🏠", "🍝", ProductCategory.DRINKS))
        assertEquals("🍝", shownSectionEmoji(false, "", "🍝", ProductCategory.DRINKS))
        // A stored "" is an answered "no section" and shows as one — it does
        // not fall through to the dictionary.
        assertEquals("", shownSectionEmoji(false, "", "", ProductCategory.DRINKS))
        assertEquals(
            ProductCategory.DRINKS.emoji,
            shownSectionEmoji(false, "", null, ProductCategory.DRINKS),
        )
        assertEquals("", shownSectionEmoji(false, "", null, null))
    }
}
