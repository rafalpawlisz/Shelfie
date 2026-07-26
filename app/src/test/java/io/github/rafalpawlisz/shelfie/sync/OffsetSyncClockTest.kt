package io.github.rafalpawlisz.shelfie.sync

import io.github.rafalpawlisz.shelfie.data.sync.OffsetSyncClock
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsetSyncClockTest {

    @Test
    fun `a stored offset corrects the device clock`() {
        // A device half an hour behind: without the correction its rows look
        // older than everyone else's and lose every conflict.
        val halfAnHour = 30 * 60 * 1000L
        val clock = OffsetSyncClock(FakeSyncStateStore(clockOffsetMillis = halfAnHour))

        val corrected = clock.now() - System.currentTimeMillis()

        assertTrue(
            "expected roughly half an hour of correction, got ${corrected}ms",
            abs(corrected - halfAnHour) < 1_000,
        )
    }

    @Test
    fun `no offset means the plain device clock`() {
        val clock = OffsetSyncClock(FakeSyncStateStore())

        assertTrue(abs(clock.now() - System.currentTimeMillis()) < 1_000)
    }
}
