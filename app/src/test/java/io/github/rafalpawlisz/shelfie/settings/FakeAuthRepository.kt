package io.github.rafalpawlisz.shelfie.settings

import io.github.rafalpawlisz.shelfie.data.AuthRepository
import io.github.rafalpawlisz.shelfie.data.AuthUser
import io.github.rafalpawlisz.shelfie.data.LinkOutcome
import io.github.rafalpawlisz.shelfie.data.SignInResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAuthRepository(
    initialUser: AuthUser? = null,
) : AuthRepository {

    private val current = MutableStateFlow(initialUser)

    /** What the next Google sign-in does; mirrors the real link/collision fork. */
    var googleOutcome: LinkOutcome = LinkOutcome.LINKED

    /** Raised by every sign-in path when set, standing in for a network failure. */
    var failure: Exception? = null

    var anonymousSignIns = 0
        private set

    override fun observeUser(): Flow<AuthUser?> = current

    override suspend fun ensureSignedIn(): AuthUser {
        current.value?.let { return it }
        failure?.let { throw it }
        anonymousSignIns++
        return AuthUser(
            uid = "anon-$anonymousSignIns",
            displayName = null,
            email = null,
            isAnonymous = true,
        ).also { current.value = it }
    }

    override suspend fun linkOrSignInWithGoogle(idToken: String): SignInResult {
        failure?.let { throw it }
        val user = when (googleOutcome) {
            // Linking keeps the identity and only enriches it.
            LinkOutcome.LINKED -> (current.value ?: ensureSignedIn()).copy(
                displayName = "Rafal",
                email = "rafal@example.com",
                isAnonymous = false,
            )
            // The credential's own account takes over: a different uid.
            LinkOutcome.SWITCHED -> AuthUser(
                uid = "google-uid",
                displayName = "Rafal",
                email = "rafal@example.com",
                isAnonymous = false,
            )
        }
        current.value = user
        return SignInResult(user, googleOutcome)
    }

    override suspend fun signInWithEmail(email: String, password: String): SignInResult {
        failure?.let { throw it }
        val user = AuthUser(
            uid = "email-uid",
            displayName = null,
            email = email,
            isAnonymous = false,
        )
        current.value = user
        return SignInResult(user, LinkOutcome.SWITCHED)
    }

    override fun signOut() {
        current.value = null
    }
}
