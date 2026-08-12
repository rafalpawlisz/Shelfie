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
        assertEquals("💊", DecorationSuggester.suggest("elektrolity"))
    }

    @Test
    fun `the batch of names that had no emoji either`() {
        assertEquals("🌰", DecorationSuggester.suggest("owoce liofilizowane"))
        assertEquals("🥫", DecorationSuggester.suggest("kimchi"))
        assertEquals("🥭", DecorationSuggester.suggest("liczi"))
        assertEquals("🥫", DecorationSuggester.suggest("mirin"))
        assertEquals("🥫", DecorationSuggester.suggest("pulpa z marakui"))
        assertEquals("🥫", DecorationSuggester.suggest("specjał mięsny"))
        assertEquals("🍲", DecorationSuggester.suggest("bulion drobiowy"))
    }

    @Test
    fun `a paste wears what it is made of, not a bowl of noodles`() {
        assertEquals("🌰", DecorationSuggester.suggest("pasta z prażonych migdałów"))
        assertEquals("🪥", DecorationSuggester.suggest("pasta do zębów"))
        assertEquals("🍝", DecorationSuggester.suggest("makaron"))
    }

    @Test
    fun `paper you eat is not paper you clean with`() {
        assertEquals("🫓", DecorationSuggester.suggest("papier do sajgonek"))
        assertEquals("🫓", DecorationSuggester.suggest("papier ryżowy"))
        assertEquals("🧻", DecorationSuggester.suggest("papier toaletowy"))
        assertEquals("🧻", DecorationSuggester.suggest("papier do pieczenia"))
    }

    @Test
    fun `tinned things wear the tin, fresh ones wear themselves`() {
        assertEquals("🥫", DecorationSuggester.suggest("krojone pomidory w puszce"))
        assertEquals("🍅", DecorationSuggester.suggest("pomidory"))
        assertEquals("🥫", DecorationSuggester.suggest("mleko zagęszczone"))
        assertEquals("🥛", DecorationSuggester.suggest("mleko"))
    }

    @Test
    fun `a pistachio paste wears the nut it is made of`() {
        assertEquals("🌰", DecorationSuggester.suggest("pasta pistacjowa"))
        assertEquals("🌰", DecorationSuggester.suggest("pistacje"))
    }

    @Test
    fun `a cream you rub in looks nothing like one you spread`() {
        assertEquals("🧴", DecorationSuggester.suggest("krem z filtrem"))
        assertEquals("🧴", DecorationSuggester.suggest("krem do rąk"))
        assertEquals("🫙", DecorationSuggester.suggest("krem czekoladowy"))
    }

    @Test
    fun `cooking fat pours like the oils it sits with`() {
        assertEquals("🫗", DecorationSuggester.suggest("tłuszcz w sprayu"))
        assertEquals("🥓", DecorationSuggester.suggest("smalec"))
    }

    @Test
    fun `a beetroot ferment wears a drink, not the vegetable it came from`() {
        assertEquals("🧃", DecorationSuggester.suggest("zakwas z buraka"))
        assertEquals("🥫", DecorationSuggester.suggest("zakwas na żurek"))
        assertEquals("🥕", DecorationSuggester.suggest("buraki"))
    }

    @Test
    fun `chanterelles wear a mushroom, turmeric keeps its spice jar`() {
        assertEquals("🍄", DecorationSuggester.suggest("kurki"))
        assertEquals("🧂", DecorationSuggester.suggest("kurkuma"))
    }

    @Test
    fun `an unknown name simply has no decoration`() {
        assertNull(DecorationSuggester.suggest("zgrzeblarka"))
        assertNull(DecorationSuggester.suggest(""))
        assertNull(DecorationSuggester.suggest("   "))
    }
}
