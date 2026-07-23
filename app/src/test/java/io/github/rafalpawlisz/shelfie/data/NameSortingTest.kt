package io.github.rafalpawlisz.shelfie.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class NameSortingTest {

    private val collator = nameCollator(Locale.of("pl", "PL"))

    private fun sort(vararg names: String): List<String> =
        names.toList().sortedWith { a, b -> collator.compare(a, b) }

    @Test
    fun `orders polish diacritics as their own letters, not after z`() {
        assertEquals(
            listOf("Ananas", "Cukier", "Ćwikła", "Łosoś", "Zupa"),
            sort("Zupa", "Łosoś", "Ćwikła", "Cukier", "Ananas"),
        )
    }

    @Test
    fun `is case insensitive`() {
        // ASCII byte order would put lowercase "banan" after "Cukier"; the
        // collator keeps alphabetical order regardless of case.
        assertEquals(
            listOf("Ananas", "banan", "Cukier"),
            sort("banan", "Cukier", "Ananas"),
        )
    }
}
