package io.github.rafalpawlisz.shelfie.data

import kotlinx.coroutines.flow.Flow

/**
 * The install's identity, and nothing more.
 *
 * There is no sign-in anywhere in the app: every account is the anonymous one
 * the app creates for itself, so an identity is a uid and carries no name,
 * address or anything else worth showing. What protects a household is its
 * invite code, which is also the only thing needed to reach it from another
 * device — see [HouseholdRepository].
 */
interface AuthRepository {
    /** The current uid, or null before the first session exists. */
    fun observeUid(): Flow<String?>

    /**
     * The current uid, signing in anonymously if there is none. Safe to call
     * repeatedly; only the first call for an install hits the network.
     */
    suspend fun ensureSignedIn(): String

    /**
     * Delete the identity itself. Called after leaving a household, where it
     * has served its purpose: an anonymous account outside a household owns
     * nothing, cannot be signed back into, and would otherwise sit in the
     * project forever. A later household action creates a fresh one.
     *
     * Local data is untouched — this deletes the account, not the pantry.
     */
    suspend fun deleteAccount()
}
