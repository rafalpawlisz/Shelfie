package io.github.rafalpawlisz.shelfie.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiSuggesterTest {

    @Test
    fun `matches a plain name`() {
        assertEquals("🥛", EmojiSuggester.suggest("mleko"))
        assertEquals("🍞", EmojiSuggester.suggest("chleb"))
        assertEquals("🥕", EmojiSuggester.suggest("marchewka"))
    }

    @Test
    fun `inflected forms reach the same entry`() {
        // The whole point of the stemmer: nobody types the dictionary form.
        val apple = EmojiSuggester.suggest("jabłko")
        assertEquals(apple, EmojiSuggester.suggest("jabłka"))
        assertEquals(apple, EmojiSuggester.suggest("jabłkach"))
        assertEquals(EmojiSuggester.suggest("pomidor"), EmojiSuggester.suggest("pomidory"))
        assertEquals(EmojiSuggester.suggest("ser"), EmojiSuggester.suggest("sera"))
        assertEquals(EmojiSuggester.suggest("ryż"), EmojiSuggester.suggest("ryżu"))
    }

    @Test
    fun `irregular plurals are listed rather than derived`() {
        // "jajek" is not reachable from "jajko" by stripping an ending, so the
        // dictionary carries it; the test exists so removing it gets noticed.
        assertEquals("🥚", EmojiSuggester.suggest("jajka"))
        assertEquals("🥚", EmojiSuggester.suggest("jajek"))
    }

    @Test
    fun `diacritics are optional on input`() {
        assertEquals(EmojiSuggester.suggest("masło"), EmojiSuggester.suggest("maslo"))
        assertEquals(EmojiSuggester.suggest("ogórek"), EmojiSuggester.suggest("ogorek"))
        assertEquals(EmojiSuggester.suggest("żelki"), EmojiSuggester.suggest("zelki"))
    }

    @Test
    fun `case and stray punctuation do not matter`() {
        assertEquals("🥛", EmojiSuggester.suggest("MLEKO"))
        assertEquals("🥛", EmojiSuggester.suggest("  Mleko!  "))
        assertEquals("🧀", EmojiSuggester.suggest("ser (żółty)"))
    }

    @Test
    fun `the head noun wins in a multi-word name`() {
        // Polish leads with the noun, so the first word that means something is
        // the subject: oat milk is milk, yellow cheese is cheese.
        assertEquals("🥛", EmojiSuggester.suggest("mleko owsiane"))
        assertEquals("🧀", EmojiSuggester.suggest("ser żółty"))
        assertEquals("🧃", EmojiSuggester.suggest("sok pomarańczowy"))
    }

    @Test
    fun `a phrase beats its own words`() {
        // "papier" alone is paper towels; the phrase is more specific, and
        // "masło orzechowe" is peanut butter rather than butter.
        assertEquals("🧻", EmojiSuggester.suggest("papier toaletowy"))
        assertEquals("🥜", EmojiSuggester.suggest("masło orzechowe"))
        assertEquals("🧴", EmojiSuggester.suggest("płyn do naczyń"))
    }

    @Test
    fun `an unknown name suggests nothing`() {
        assertNull(EmojiSuggester.suggest("zgrzeblarka"))
        assertNull(EmojiSuggester.suggest(""))
        assertNull(EmojiSuggester.suggest("   "))
        assertNull(EmojiSuggester.suggest("xyz"))
    }

    @Test
    fun `the same name always gives the same emoji`() {
        // Duplicate keywords across entries are resolved by dictionary order,
        // so a suggestion cannot flicker between renders.
        repeat(5) { assertEquals("🧀", EmojiSuggester.suggest("serek")) }
        repeat(5) { assertEquals("🥫", EmojiSuggester.suggest("zupa")) }
    }

    @Test
    fun `short words are not stemmed into nothing`() {
        assertEquals("🧃", EmojiSuggester.suggest("sok"))
        assertEquals("🧀", EmojiSuggester.suggest("ser"))
        assertEquals("🧂", EmojiSuggester.suggest("sól"))
    }
}
