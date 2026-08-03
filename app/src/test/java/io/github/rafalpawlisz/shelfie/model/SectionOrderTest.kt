package io.github.rafalpawlisz.shelfie.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectionOrderTest {

    @Test
    fun `no stored order means the declaration order`() {
        assertEquals(ProductCategory.entries.toList(), SectionOrder.parse(null))
        assertEquals(ProductCategory.entries.toList(), SectionOrder.parse(""))
    }

    @Test
    fun `a stored order leads, and everything else follows in declaration order`() {
        // What a real customisation looks like: two aisles pulled to the front,
        // the remaining fourteen left alone.
        val parsed = SectionOrder.parse("HYGIENE,BREAD")

        assertEquals(ProductCategory.HYGIENE, parsed[0])
        assertEquals(ProductCategory.BREAD, parsed[1])
        assertEquals(ProductCategory.entries.size, parsed.size)
        assertEquals(ProductCategory.entries.size, parsed.toSet().size)
        // The rest kept their relative order.
        assertEquals(ProductCategory.PRODUCE, parsed[2])
    }

    @Test
    fun `junk in the column cannot make a broken order`() {
        // A hand-edited document, a section renamed in a later version, a
        // duplicate: the result must still be every section exactly once,
        // because the sort ranks against it and the editor lists it.
        val parsed = SectionOrder.parse("DAIRY,NOT_A_SECTION,DAIRY, BREAD ,")

        assertEquals(listOf(ProductCategory.DAIRY, ProductCategory.BREAD), parsed.take(2))
        assertEquals(ProductCategory.entries.size, parsed.size)
        assertEquals(ProductCategory.entries.size, parsed.toSet().size)
    }

    @Test
    fun `the default order stores nothing`() {
        // "Not customised" stays absent in the database and in the synced
        // document, so a list nobody touched carries no opinion at all.
        assertNull(SectionOrder.store(ProductCategory.entries.toList()))
        assertEquals(
            "BREAD,PRODUCE",
            SectionOrder.store(listOf(ProductCategory.BREAD, ProductCategory.PRODUCE)),
        )
    }

    @Test
    fun `a round trip through storage keeps the order`() {
        val custom = listOf(ProductCategory.PHARMACY, ProductCategory.FROZEN) +
            ProductCategory.entries.filterNot {
                it == ProductCategory.PHARMACY || it == ProductCategory.FROZEN
            }

        assertEquals(custom, SectionOrder.parse(SectionOrder.store(custom)))
    }

    @Test
    fun `rank puts the sectionless last, whatever the order`() {
        val order = SectionOrder.parse("HOME")

        assertEquals(0, order.rankOf(ProductCategory.HOME))
        assertEquals(order.size, order.rankOf(null))
    }
}
