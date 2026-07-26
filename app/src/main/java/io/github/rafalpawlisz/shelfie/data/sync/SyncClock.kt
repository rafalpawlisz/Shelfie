package io.github.rafalpawlisz.shelfie.data.sync

/**
 * Time source for row timestamps, nudged toward server time.
 *
 * Last-write-wins compares timestamps written by different devices, so a device
 * whose clock runs fast does not merely win conflicts — it also ignores the
 * other device's newer edits (they look older) while its own unconditional
 * writes keep overwriting them, and the household stays split until real time
 * passes the skew. A cloned emulator half an hour off made this concrete.
 *
 * The offset is measured against the server timestamp the household's
 * lastActiveAt already carries, so no extra plumbing is needed.
 */
fun interface SyncClock {
    fun now(): Long
}

/** The device clock, with a persisted correction applied. */
class OffsetSyncClock(private val syncState: SyncStateStore) : SyncClock {
    override fun now(): Long = System.currentTimeMillis() + syncState.clockOffsetMillis
}
