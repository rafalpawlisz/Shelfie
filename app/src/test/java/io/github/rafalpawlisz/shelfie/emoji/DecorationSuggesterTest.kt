package io.github.rafalpawlisz.shelfie.emoji

import io.github.rafalpawlisz.shelfie.model.ProductCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecorationSuggesterTest {

    @Test
    fun `a name gets its own face, not its aisle's`() {
        // The whole point of bringing this dictionary back: everything in the
        // produce aisle used to look identical on the list.
        assertEquals("🥕", DecorationSuggester.suggest("marchewka"))
        assertEquals("🍌", DecorationSuggester.suggest("banany"))
        assertEquals("🧀", DecorationSuggester.suggest("ser żółty"))
        assertEquals("☕", DecorationSuggester.suggest("kawa"))
        // ...while all three of those are filed under two sections only.
        assertEquals(ProductCategory.PRODUCE, CategorySuggester.suggest("marchewka"))
        assertEquals(ProductCategory.PRODUCE, CategorySuggester.suggest("banany"))
        assertNotEquals(
            DecorationSuggester.suggest("marchewka"),
            DecorationSuggester.suggest("banany"),
        )
    }

    @Test
    fun `inflection and diacritics reach the same entry`() {
        val apple = DecorationSuggester.suggest("jabłko")
        assertEquals("🍎", apple)
        assertEquals(apple, DecorationSuggester.suggest("jablka"))
        assertEquals(apple, DecorationSuggester.suggest("JABŁKACH"))
    }

    @Test
    fun `the longer phrase wins here too`() {
        // Shared with the section dictionary through WordDictionary, so this is
        // the test that says the sharing is real.
        assertEquals("🥫", DecorationSuggester.suggest("ogórki kiszone"))
        assertEquals("🥒", DecorationSuggester.suggest("ogórki"))
        assertEquals("🥜", DecorationSuggester.suggest("masło orzechowe"))
        assertEquals("🧈", DecorationSuggester.suggest("masło"))
    }

    @Test
    fun `words collected after the dictionary was retired are in it`() {
        assertEquals("🧤", DecorationSuggester.suggest("rękawiczki nitrylowe"))
        assertEquals("🧻", DecorationSuggester.suggest("serwetki"))
        assertEquals("🪥", DecorationSuggester.suggest("nić dentystyczna"))
        assertEquals("🪥", DecorationSuggester.suggest("płyn do płukania ust"))
    }

    @Test
    fun `a supplement wears a pill, not its ingredient`() {
        assertEquals("💊", DecorationSuggester.suggest("suplement z czerwonego ryżu"))
        assertEquals("🍚", DecorationSuggester.suggest("ryż"))
    }

    @Test
    fun `an unknown name simply has no decoration`() {
        assertNull(DecorationSuggester.suggest("zgrzeblarka"))
        assertNull(DecorationSuggester.suggest(""))
        assertNull(DecorationSuggester.suggest("   "))
    }
}
