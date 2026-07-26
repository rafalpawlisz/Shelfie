package io.github.rafalpawlisz.shelfie.data.sync

import android.content.Context

/**
 * Durable, device-local memory of what this device has already synced.
 *
 * It answers the one question the initial reconcile cannot answer from
 * snapshots alone: is a local row missing remotely because someone deleted
 * it, or because it was created here and never made it to the server?
 * Everything newer than [lastSyncedAt] falls in the second category.
 */
interface SyncStateStore {
    /** Household this device last reconciled against, or null if never. */
    var lastSyncedHouseholdId: String?

    /**
     * Client-clock time of the last completed reconcile (0 if never). Compared
     * only against locally written updatedAt values from the same device, so
     * cross-device clock skew is irrelevant here.
     */
    var lastSyncedAt: Long

    /**
     * How far this device's clock is behind the server (negative if ahead),
     * measured once per session and applied to every timestamp we write. See
     * [SyncClock] for why an uncorrected clock splits a household.
     */
    var clockOffsetMillis: Long
}

class SharedPreferencesSyncStateStore(context: Context) : SyncStateStore {

    private val prefs =
        context.applicationContext.getSharedPreferences("sync_state", Context.MODE_PRIVATE)

    override var lastSyncedHouseholdId: String?
        get() = prefs.getString(KEY_HOUSEHOLD_ID, null)
        set(value) {
            prefs.edit().putString(KEY_HOUSEHOLD_ID, value).apply()
        }

    override var lastSyncedAt: Long
        get() = prefs.getLong(KEY_LAST_SYNCED_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNCED_AT, value).apply()
        }

    override var clockOffsetMillis: Long
        get() = prefs.getLong(KEY_CLOCK_OFFSET, 0L)
        set(value) {
            prefs.edit().putLong(KEY_CLOCK_OFFSET, value).apply()
        }

    private companion object {
        const val KEY_HOUSEHOLD_ID = "lastSyncedHouseholdId"
        const val KEY_LAST_SYNCED_AT = "lastSyncedAt"
        const val KEY_CLOCK_OFFSET = "clockOffsetMillis"
    }
}
