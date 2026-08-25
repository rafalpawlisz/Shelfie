package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.ui.pantry.OneOffSplit
import io.github.rafalpawlisz.shelfie.ui.pantry.splitOneOffQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneOffSplitTest {

    private fun split(query: String) = splitOneOffQuery(query)

    private fun assertSplit(
        query: String,
        name: String,
        amount: Int? = null,
        unit: String? = null,
    ) = assertEquals(OneOffSplit(name, amount, unit), split(query))

    @Test
    fun `a trailing amount with unit splits off`() {
        assertSplit("marchew 200 g", name = "marchew", amount = 200, unit = "g")
        assertSplit("mleko 1 l", name = "mleko", amount = 1, unit = "l")
        assertSplit("chleb 2 szt", name = "chleb", amount = 2, unit = "szt")
        assertSplit("pomidory 1 kg", name = "pomidory", amount = 1, unit = "kg")
    }

    @Test
    fun `a unit glued to the number splits off too`() {
        assertSplit("marchew 200g", name = "marchew", amount = 200, unit = "g")
        assertSplit("masło 250g", name = "masło", amount = 250, unit = "g")
    }

    @Test
    fun `a trailing amount without unit splits off bare`() {
        assertSplit("marchew 200", name = "marchew", amount = 200)
        assertSplit("ziemniaki 2", name = "ziemniaki", amount = 2)
    }

    @Test
    fun `no quantity leaves the whole text as the name`() {
        assertSplit("marchew", name = "marchew")
        assertSplit("", name = "")
        assertSplit("   ", name = "")
    }

    @Test
    fun `a number with no name stays the name`() {
        // Splitting "200" into an amount of an empty name would make a row
        // with nothing on it; a bare "200 g" is the same — no thing to measure.
        assertSplit("200", name = "200")
        assertSplit("200 g", name = "200 g")
    }

    @Test
    fun `a zero amount is not a quantity`() {
        // "mąka 00" is a kind of flour, not an amount of nothing.
        assertSplit("mąka 00", name = "mąka 00")
        assertSplit("kasza 0 g", name = "kasza 0 g")
    }

    @Test
    fun `punctuation after the number is not a unit`() {
        // "3,2%" is a property of the thing, not a quantity; the text is kept.
        assertSplit("mleko 3,2%", name = "mleko 3,2%")
        assertSplit("krem 30%", name = "krem 30%")
    }

    @Test
    fun `a word after the quantity keeps the whole text as the name`() {
        // One trailing unit token is parsed; anything more is not a quantity
        // anybody meant, so nothing splits off.
        assertSplit("marchew 200 g ekstra", name = "marchew 200 g ekstra")
    }

    @Test
    fun `the last quantity is the amount, the rest is the name`() {
        assertSplit("ryż 200 g 400 g", name = "ryż 200 g", amount = 400, unit = "g")
    }

    @Test
    fun `stray spaces do not change the answer`() {
        assertSplit("  marchew  200 g  ", name = "marchew", amount = 200, unit = "g")
    }
}
