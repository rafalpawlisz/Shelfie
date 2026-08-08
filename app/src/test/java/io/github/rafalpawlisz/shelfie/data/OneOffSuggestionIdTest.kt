package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.data.local.oneOffSuggestionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The id is what keeps the household's vocabulary from growing duplicates: both
 * phones must derive the same one from the same word, or each would write its
 * own document for "znicze".
 */
class OneOffSuggestionIdTest {

    @Test
    fun `the same word gives the same id, however it was typed`() {
        val canonical = oneOffSuggestionId("znicze")
        assertEquals(canonical, oneOffSuggestionId("  znicze  "))
        assertEquals(canonical, oneOffSuggestionId("Znicze"))
        assertEquals(canonical, oneOffSuggestionId("ZNICZE"))
    }

    @Test
    fun `inner spacing is collapsed, not ignored`() {
        // "papier toaletowy" typed with a double space is the same shopping
        // item; "papiertoaletowy" is a different word and stays one.
        assertEquals(
            oneOffSuggestionId("papier toaletowy"),
            oneOffSuggestionId("papier  toaletowy"),
        )
        assertNotEquals(
            oneOffSuggestionId("papier toaletowy"),
            oneOffSuggestionId("papiertoaletowy"),
        )
    }

    @Test
    fun `different words give different ids`() {
        assertNotEquals(oneOffSuggestionId("znicze"), oneOffSuggestionId("wiadro"))
    }

    @Test
    fun `the id is safe to use as a document path segment`() {
        // Names contain what people type — slashes, dots, emoji — and this
        // doubles as the Firestore document id, where a slash would split the
        // path and a bare ".." would not be addressable at all.
        for (name in listOf("sok 1/2 l", "..", ".", "ser 40% tłuszczu", "🍄 kurki", "a".repeat(500))) {
            val id = oneOffSuggestionId(name)
            assertEquals("$name should be hex", 40, id.length)
            assertEquals("$name should be hex", true, id.all { it in "0123456789abcdef" })
        }
    }
}
