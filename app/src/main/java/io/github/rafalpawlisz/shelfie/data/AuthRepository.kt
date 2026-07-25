package io.github.rafalpawlisz.shelfie.data

import kotlinx.coroutines.flow.Flow

/** The signed-in account, or null when signed out. */
data class AuthUser(
    val uid: String,
    val displayName: String?,
    val email: String?,
)

interface AuthRepository {
    fun observeUser(): Flow<AuthUser?>

    /** Exchange a Google ID token (from Credential Manager) for a session. */
    suspend fun signInWithGoogleIdToken(idToken: String)

    /** Email + password sign-in; accounts are created in the Firebase console. */
    suspend fun signInWithEmail(email: String, password: String)

    fun signOut()
}
