package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.ui.pantry.expiryYearRange
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window of years the date picker offers. Mostly a crash test: a selected
 * date outside the range makes the Material picker throw rather than clamp, so
 * the range has to bend around whatever is already stored.
 */
class ExpiryYearRangeTest {

    private val thisYear = LocalDate.now().year

    @Test
    fun `covers now and the shelf life of a tin`() {
        val range = expiryYearRange("")
        assertTrue(thisYear in range)
        assertTrue(thisYear + 10 in range)
        // A jar bought last year and long expired is still editable.
        assertTrue(thisYear - 1 in range)
    }

    @Test
    fun `stretches to hold a date already stored, however far out`() {
        assertTrue(2019 in expiryYearRange("2019-03-01"))
        assertTrue(2099 in expiryYearRange("2099-12-31"))
        // And stretching for an old date does not cost the near years.
        assertTrue(thisYear in expiryYearRange("2019-03-01"))
    }

    @Test
    fun `a value that is not a date leaves the range alone`() {
        assertEquals(expiryYearRange(""), expiryYearRange("kiedyś tam"))
        assertTrue(thisYear in expiryYearRange("kiedyś tam"))
    }
}
