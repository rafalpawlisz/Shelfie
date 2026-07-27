package io.github.rafalpawlisz.shelfie.data

import io.github.rafalpawlisz.shelfie.model.Household
import kotlinx.coroutines.flow.Flow

/** Thrown by [HouseholdRepository.joinHousehold] for a code that resolves to nothing. */
class InvalidInviteCodeException : Exception("invite code does not exist")

interface HouseholdRepository {

    /** The household [uid] belongs to, or null; live-updates on membership changes. */
    fun observeHousehold(uid: String): Flow<Household?>

    /** Create a household with [uid] as the sole member and point the user at it. */
    suspend fun createHousehold(uid: String, name: String)

    /**
     * Join the household behind [code]. When the user already belongs to
     * another household this is a switch: they leave the old one in the same
     * batch (and an emptied old household is deleted along with its code).
     * The caller is responsible for confirming the switch with the user.
     */
    suspend fun joinHousehold(uid: String, code: String)

    /**
     * Leave the current household without joining another: the user goes back
     * to solo mode (local data stays, syncing stops). The household itself is
     * kept even when emptied, so it can be rejoined by code.
     */
    suspend fun leaveHousehold(uid: String)

    /**
     * Leave and take the household with you: every document under it, its
     * invite code, the household itself, and the caller's pointer. For a sole
     * member who wants the shared copy gone rather than kept for recovery.
     *
     * Local data is not touched — this deletes the household, not the pantry.
     *
     * Order is forced by three things. The pointer goes first, because it is
     * what drives the sync session and deleting documents underneath a live
     * session makes the pull side mirror those deletions into Room — the local
     * pantry, which is exactly what this promises to keep. The subcollections
     * go next, one document at a time (Firestore has no cascade) and while
     * membership in the household document still grants access to them. The
     * household and its code go last.
     *
     * An interrupted run leaves the caller outside a household that still
     * exists, with its code still valid — rejoinable, and retryable.
     */
    suspend fun deleteHousehold(uid: String)

    /** Rename the household; every member sees it through [observeHousehold]. */
    suspend fun renameHousehold(householdId: String, name: String)

    /**
     * Stamp the household, and [uid] within it, as in use (server time).
     * Written once per sync session, so the project owner can tell live
     * households from abandoned ones and live members from membership entries
     * whose owner is gone — nothing in the app reads either for that purpose.
     *
     * Returns how far this device's clock is behind the server, measured from
     * the stamp it just wrote, or null when it could not be determined
     * (offline). See [io.github.rafalpawlisz.shelfie.data.sync.SyncClock].
     */
    suspend fun markHouseholdActive(householdId: String, uid: String): Long?
}
