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
}
