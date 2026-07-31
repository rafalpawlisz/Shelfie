package io.github.rafalpawlisz.shelfie.emoji

import io.github.rafalpawlisz.shelfie.model.ProductCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategorySuggesterTest {

    @Test
    fun `matches a plain name to its store section`() {
        assertEquals(ProductCategory.DAIRY, CategorySuggester.suggest("mleko"))
        assertEquals(ProductCategory.BREAD, CategorySuggester.suggest("chleb"))
        assertEquals(ProductCategory.PRODUCE, CategorySuggester.suggest("marchewka"))
        assertEquals(ProductCategory.HOME, CategorySuggester.suggest("żarówka"))
    }

    @Test
    fun `inflected forms reach the same entry`() {
        // The whole point of the stemmer: nobody types the dictionary form.
        val apple = CategorySuggester.suggest("jabłko")
        assertEquals(apple, CategorySuggester.suggest("jabłka"))
        assertEquals(apple, CategorySuggester.suggest("jabłkach"))
        assertEquals(CategorySuggester.suggest("pomidor"), CategorySuggester.suggest("pomidory"))
        assertEquals(CategorySuggester.suggest("ser"), CategorySuggester.suggest("sera"))
        assertEquals(CategorySuggester.suggest("ryż"), CategorySuggester.suggest("ryżu"))
    }

    @Test
    fun `irregular plurals are listed rather than derived`() {
        // "jajek" is not reachable from "jajko" by stripping an ending, so the
        // dictionary carries it; the test exists so removing it gets noticed.
        assertEquals(ProductCategory.DAIRY, CategorySuggester.suggest("jajka"))
        assertEquals(ProductCategory.DAIRY, CategorySuggester.suggest("jajek"))
    }

    @Test
    fun `diacritics and case are optional on input`() {
        assertEquals(CategorySuggester.suggest("masło"), CategorySuggester.suggest("maslo"))
        assertEquals(CategorySuggester.suggest("ogórek"), CategorySuggester.suggest("ogorek"))
        assertEquals(ProductCategory.DAIRY, CategorySuggester.suggest("MLEKO"))
        assertEquals(ProductCategory.DAIRY, CategorySuggester.suggest("  Mleko!  "))
    }

    @Test
    fun `the head noun wins in a multi-word name`() {
        // Polish leads with the noun: oat milk is milk, yellow cheese cheese.
        assertEquals(ProductCategory.DAIRY, CategorySuggester.suggest("mleko owsiane"))
        assertEquals(ProductCategory.DAIRY, CategorySuggester.suggest("ser żółty"))
        assertEquals(ProductCategory.DRINKS, CategorySuggester.suggest("sok pomarańczowy"))
    }

    @Test
    fun `a phrase beats its own words`() {
        // Pickled things live in the jar aisle, not among the vegetables; the
        // single-word answers are still there for the plain ones.
        assertEquals(ProductCategory.CANNED, CategorySuggester.suggest("ogórki kiszone"))
        assertEquals(ProductCategory.PRODUCE, CategorySuggester.suggest("ogórki"))
        assertEquals(ProductCategory.CANNED, CategorySuggester.suggest("kapusta kiszona"))
        assertEquals(ProductCategory.PRODUCE, CategorySuggester.suggest("kapusta"))
        assertEquals(ProductCategory.CANNED, CategorySuggester.suggest("masło orzechowe"))
        assertEquals(ProductCategory.DAIRY, CategorySuggester.suggest("masło"))
        // A loaf is bread even though the sweets aisle owns the word "baton".
        assertEquals(ProductCategory.BREAD, CategorySuggester.suggest("baton chleb"))
        assertEquals(ProductCategory.SWEETS, CategorySuggester.suggest("baton"))
    }

    @Test
    fun `the longer phrase wins over a phrase inside it`() {
        // Frozen fish sticks are in the freezer, plain ones at the fish
        // counter. Dictionary order cannot decide this — the longer phrase has
        // to, or the specific entry is unreachable.
        assertEquals(ProductCategory.FROZEN, CategorySuggester.suggest("paluszki rybne mrożone"))
        assertEquals(ProductCategory.FISH, CategorySuggester.suggest("paluszki rybne"))
    }

    @Test
    fun `a parcel is not a doughnut`() {
        // "paczka" stems onto the bakery's "paczki", so the word is left out of
        // the dictionary rather than kept as an entry that answers "bread".
        assertEquals(ProductCategory.HOME, CategorySuggester.suggest("przesyłka"))
        assertEquals(ProductCategory.BREAD, CategorySuggester.suggest("pączki"))
    }

    @Test
    fun `a real shopping list lands in the right aisles`() {
        assertEquals(ProductCategory.DAIRY, CategorySuggester.suggest("śmietana 18%"))
        assertEquals(ProductCategory.DRY_GOODS, CategorySuggester.suggest("budyń waniliowy"))
        assertEquals(ProductCategory.PRODUCE, CategorySuggester.suggest("buraki"))
        assertEquals(ProductCategory.PRODUCE, CategorySuggester.suggest("szczypiorek"))
        assertEquals(ProductCategory.MEAT, CategorySuggester.suggest("mięso mielone"))
        assertEquals(ProductCategory.FISH, CategorySuggester.suggest("paluszki rybne"))
        assertEquals(ProductCategory.BREAD, CategorySuggester.suggest("bagietka"))
        assertEquals(ProductCategory.DRY_GOODS, CategorySuggester.suggest("kasza gryczana"))
        assertEquals(ProductCategory.DRY_GOODS, CategorySuggester.suggest("cukier puder"))
        assertEquals(ProductCategory.SPICES, CategorySuggester.suggest("olej rzepakowy"))
        assertEquals(ProductCategory.SWEETS, CategorySuggester.suggest("czekolada"))
        assertEquals(ProductCategory.ALCOHOL, CategorySuggester.suggest("wino czerwone"))
        assertEquals(ProductCategory.CLEANING, CategorySuggester.suggest("papier toaletowy"))
        assertEquals(ProductCategory.CLEANING, CategorySuggester.suggest("płyn do naczyń"))
        assertEquals(ProductCategory.HYGIENE, CategorySuggester.suggest("podpaski"))
        assertEquals(ProductCategory.PHARMACY, CategorySuggester.suggest("witaminy"))
        assertEquals(ProductCategory.FROZEN, CategorySuggester.suggest("pizza"))
    }

    @Test
    fun `cleaning gloves reach the cleaning aisle in every form`() {
        assertEquals(ProductCategory.CLEANING, CategorySuggester.suggest("rękawiczki nitrylowe"))
        assertEquals(ProductCategory.CLEANING, CategorySuggester.suggest("nitrylowe rękawiczki"))
        assertEquals(ProductCategory.CLEANING, CategorySuggester.suggest("rękawiczki"))
        assertEquals(ProductCategory.CLEANING, CategorySuggester.suggest("rękawiczek"))
        assertEquals(ProductCategory.CLEANING, CategorySuggester.suggest("rękawice gumowe"))
    }

    @Test
    fun `napkins and dental floss found their aisles`() {
        assertEquals(ProductCategory.CLEANING, CategorySuggester.suggest("serwetki"))
        assertEquals(ProductCategory.CLEANING, CategorySuggester.suggest("serwetka"))
        // "nić" is three letters, so the stemmer leaves it alone and the
        // dictionary has to carry that exact shape.
        assertEquals(ProductCategory.HYGIENE, CategorySuggester.suggest("nić dentystyczna"))
        assertEquals(ProductCategory.HYGIENE, CategorySuggester.suggest("nic dentystyczna"))
        assertEquals(ProductCategory.HYGIENE, CategorySuggester.suggest("nici dentystyczne"))
        assertEquals(ProductCategory.HYGIENE, CategorySuggester.suggest("dentystyczna nić"))
    }

    @Test
    fun `an unknown name suggests nothing`() {
        assertNull(CategorySuggester.suggest("zgrzeblarka"))
        assertNull(CategorySuggester.suggest(""))
        assertNull(CategorySuggester.suggest("   "))
        assertNull(CategorySuggester.suggest("xyz"))
    }

    @Test
    fun `the same name always gives the same section`() {
        // Duplicate keywords across entries are resolved by dictionary order,
        // so a suggestion cannot flicker between renders.
        repeat(5) { assertEquals(ProductCategory.DAIRY, CategorySuggester.suggest("serek")) }
        repeat(5) { assertEquals(ProductCategory.CANNED, CategorySuggester.suggest("zupa")) }
    }

    @Test
    fun `short words are not stemmed into nothing`() {
        assertEquals(ProductCategory.DRINKS, CategorySuggester.suggest("sok"))
        assertEquals(ProductCategory.DAIRY, CategorySuggester.suggest("ser"))
        assertEquals(ProductCategory.SPICES, CategorySuggester.suggest("sól"))
    }

    @Test
    fun `every category emoji is unique - it is the storage key`() {
        val emoji = ProductCategory.entries.map { it.emoji }
        assertEquals(emoji.size, emoji.toSet().size)
    }
}
