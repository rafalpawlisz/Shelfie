package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.data.local.sectionEmojiFor
import io.github.rafalpawlisz.shelfie.model.ProductCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which section a shopping-list row shows. Three inputs decide it and the
 * interesting part is which one wins, because two of them can disagree.
 */
class SectionEmojiForTest {

    @Test
    fun `a product row answers with its product, never with its name`() {
        // The product form promises that a blank section means "no section"
        // rather than "guess", and this is where that promise is kept: "mleko"
        // filed under nothing stays under nothing.
        assertEquals(
            ProductCategory.FISH.emoji,
            sectionEmojiFor("p1", ProductCategory.FISH.emoji, "mleko", chosen = null),
        )
        assertNull(sectionEmojiFor("p1", null, "mleko", chosen = null))
        // Even a section stored on the row cannot override it — the DAO's CASE
        // guard should never let one exist, and this says what happens if it does.
        assertEquals(
            ProductCategory.FISH.emoji,
            sectionEmojiFor("p1", ProductCategory.FISH.emoji, "mleko", chosen = "🏠"),
        )
    }

    @Test
    fun `a one-off nobody corrected reads its section from its name`() {
        assertEquals(
            ProductCategory.HOME.emoji,
            sectionEmojiFor(productId = null, productEmoji = null, name = "znicz", chosen = null),
        )
        // Which is what keeps such lines improving as the dictionary does.
        assertNull(
            sectionEmojiFor(productId = null, productEmoji = null, name = "zgrzeblarka", chosen = null),
        )
    }

    @Test
    fun `a hand-picked section beats the dictionary`() {
        // The whole point: the dictionary says drinks, the shopper says the
        // baking shelf, and the shopper is the one standing in the shop.
        assertEquals(
            ProductCategory.DRY_GOODS.emoji,
            sectionEmojiFor(
                productId = null,
                productEmoji = null,
                name = "kompot",
                chosen = ProductCategory.DRY_GOODS.emoji,
            ),
        )
    }

    @Test
    fun `an explicit no section is not a fresh invitation to guess`() {
        // "" is somebody having answered "nowhere". Falling back to the name
        // here would make that answer impossible to give.
        assertNull(
            sectionEmojiFor(productId = null, productEmoji = null, name = "znicz", chosen = ""),
        )
    }
}
