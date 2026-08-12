package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.model.ProductCategory
import io.github.rafalpawlisz.shelfie.ui.pantry.SectionNote
import io.github.rafalpawlisz.shelfie.ui.pantry.sectionNoteFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The line under the store-section field. Three states, and the interesting one
 * is silence: an empty form must not accuse the user of typing an unknown name
 * before they have typed anything at all.
 */
class SectionNoteTest {

    @Test
    fun `nothing typed yet says nothing`() {
        assertNull(sectionNoteFor(name = "", selectedEmoji = "", suggestion = null))
        assertNull(sectionNoteFor(name = "   ", selectedEmoji = "", suggestion = null))
    }

    @Test
    fun `a name the dictionary has never met says so`() {
        // Which is the whole point: the gaps get noticed while adding the
        // product, not in the shop three days later.
        assertEquals(
            SectionNote.NameUnknown,
            sectionNoteFor(name = "papier do sajgonek", selectedEmoji = "", suggestion = null),
        )
    }

    @Test
    fun `picking a section by hand does not make the name known`() {
        // The note is about the name, not about the field. Having answered the
        // question yourself does not mean the app will know the answer next time.
        assertEquals(
            SectionNote.NameUnknown,
            sectionNoteFor(name = "zgrzeblarka", selectedEmoji = "🏠", suggestion = null),
        )
    }

    @Test
    fun `a suggestion the field already shows is not repeated`() {
        assertNull(
            sectionNoteFor(
                name = "mleko",
                selectedEmoji = ProductCategory.DAIRY.emoji,
                suggestion = ProductCategory.DAIRY,
            ),
        )
    }

    @Test
    fun `a suggestion the field disagrees with is spelled out`() {
        // The answer to "why is milk in the fish aisle?" — the closed field
        // otherwise hides that anything was ever suggested.
        assertEquals(
            SectionNote.FromName(ProductCategory.DAIRY),
            sectionNoteFor(
                name = "mleko",
                selectedEmoji = ProductCategory.FISH.emoji,
                suggestion = ProductCategory.DAIRY,
            ),
        )
        // A section chosen where the dictionary offered none is the same case:
        // the suggestion is what differs, so it is what gets said.
        assertEquals(
            SectionNote.FromName(ProductCategory.DAIRY),
            sectionNoteFor(name = "mleko", selectedEmoji = "", suggestion = ProductCategory.DAIRY),
        )
    }
}
