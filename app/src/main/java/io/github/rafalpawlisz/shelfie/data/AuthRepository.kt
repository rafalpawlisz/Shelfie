package io.github.rafalpawlisz.shelfie.data

import kotlinx.coroutines.flow.Flow

/** The current account, or null before the first session exists. */
data class AuthUser(
    val uid: String,
    val displayName: String?,
    val email: String?,
    /**
     * True for the account the app creates by itself on first launch. It is a
     * real Firebase identity — rules and household membership work normally —
     * but nobody can sign back into it, so it dies with this install's app
     * data. Everything the UI says about "securing" an account keys off this.
     */
    val isAnonymous: Boolean,
)

/** What signing in did to the identity the app was already using. */
enum class LinkOutcome {
    /** The credential was attached to the current uid: household untouched. */
    LINKED,

    /**
     * The credential already had its own account, which has taken over. The
     * previous uid (and its household membership) is abandoned, so the caller
     * has to get the new identity back into the household.
     */
    SWITCHED,
}

/** Who we ended up as, and at what cost to the previous identity. */
data class SignInResult(val user: AuthUser, val outcome: LinkOutcome)

interface AuthRepository {
    fun observeUser(): Flow<AuthUser?>

    /**
     * The current session, signing in anonymously if there is none. Safe to
     * call repeatedly; only the first call for an install hits the network.
     */
    suspend fun ensureSignedIn(): AuthUser

    /**
     * Attach a Google account to the current anonymous identity, keeping its
     * uid and household. Falls back to signing in as that Google account when
     * it already has one ([LinkOutcome.SWITCHED]).
     */
    suspend fun linkOrSignInWithGoogle(idToken: String): SignInResult

    /**
     * Sign in to an existing email account; accounts are created in the
     * Firebase console, never here. Deliberately not a link: linking an unused
     * address would quietly create an account from a typo, and the addresses
     * this app knows are test accounts that already exist. Always reports
     * [LinkOutcome.SWITCHED].
     */
    suspend fun signInWithEmail(email: String, password: String): SignInResult

    fun signOut()
}
