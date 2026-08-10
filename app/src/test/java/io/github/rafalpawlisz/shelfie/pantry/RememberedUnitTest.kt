package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.model.OneOffSuggestion
import io.github.rafalpawlisz.shelfie.ui.pantry.rememberedUnitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RememberedUnitTest {

    private val history = listOf(
        OneOffSuggestion(name = "kurki", unit = "g"),
        OneOffSuggestion(name = "znicze", unit = "sztuki"),
        OneOffSuggestion(name = "wiadro", unit = null),
    )

    @Test
    fun `a name the history knows brings its unit back`() {
        assertEquals("g", rememberedUnitFor("kurki", history))
        assertEquals("sztuki", rememberedUnitFor("znicze", history))
    }

    @Test
    fun `a name typed fresh brings nothing`() {
        // The bug this exists for: the unit was held as its own state, set when
        // a suggestion was picked and never cleared, so the NEXT name — typed
        // by hand, nothing to do with the first — arrived measured in grams.
        assertNull(rememberedUnitFor("zgrzeblarka", history))
        assertNull(rememberedUnitFor(null, history))
        assertNull(rememberedUnitFor("", history))
    }

    @Test
    fun `a remembered name with no unit stays without one`() {
        assertNull(rememberedUnitFor("wiadro", history))
    }

    @Test
    fun `matching is as loose as the history's own identity`() {
        // Typing a name the history already holds gives the same answer picking
        // it would have — case and stray spaces are not a different thing.
        assertEquals("g", rememberedUnitFor("Kurki", history))
        assertEquals("g", rememberedUnitFor("  kurki  ", history))
    }

    @Test
    fun `an empty history answers nothing rather than guessing`() {
        assertNull(rememberedUnitFor("kurki", emptyList()))
    }
}
