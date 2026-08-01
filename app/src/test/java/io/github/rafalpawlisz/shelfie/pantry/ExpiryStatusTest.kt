package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.ui.pantry.ExpiryStatus
import io.github.rafalpawlisz.shelfie.ui.pantry.expiringFirst
import io.github.rafalpawlisz.shelfie.ui.pantry.expiryStatusOf
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpiryStatusTest {

    private val today = LocalDate.of(2026, 8, 1)

    @Test
    fun `a date past today is expired, the day itself is not`() {
        // "Best before" means through that day, so today is still good — but
        // close enough to act on, which is what SOON says.
        assertEquals(ExpiryStatus.EXPIRED, expiryStatusOf("2026-07-31", today))
        assertEquals(ExpiryStatus.SOON, expiryStatusOf("2026-08-01", today))
    }

    @Test
    fun `the warning window is inclusive and ends where it says`() {
        assertEquals(ExpiryStatus.SOON, expiryStatusOf("2026-08-31", today))
        assertNull(expiryStatusOf("2026-09-01", today))
        // A month is generous on purpose: a jar needs weeks to be used up.
        assertEquals(ExpiryStatus.SOON, expiryStatusOf("2026-08-10", today, within = 14))
        assertNull(expiryStatusOf("2026-08-20", today, within = 14))
    }

    @Test
    fun `no date and an unreadable date both say nothing`() {
        // Nothing writes this field but the picker, but a value from another
        // phone, an older version or a hand-edited document must not crash a
        // list on its way past.
        assertNull(expiryStatusOf(null, today))
        assertNull(expiryStatusOf("", today))
        assertNull(expiryStatusOf("kiedyś", today))
        assertNull(expiryStatusOf("2026-02-30", today))
        assertNull(expiryStatusOf("01.08.2026", today))
    }

    @Test
    fun `the pinned block is soonest first and leaves the rest alone`() {
        val products = listOf(
            product("Syrop", "2026-08-15"),
            product("Ryż", null),
            product("Aspiryna", "2026-07-01"),
            product("Miód", "2027-01-01"),
            product("Kasza", "2026-08-02"),
        )

        val expiring = products.expiringFirst(today)

        assertEquals(listOf("Aspiryna", "Kasza", "Syrop"), expiring.map { it.name })
    }

    private fun product(name: String, expiresOn: String?) = Product(
        id = name,
        name = name,
        quantity = 1,
        unit = null,
        minQuantity = null,
        notes = null,
        emoji = null,
        expiresOn = expiresOn,
    )
}
