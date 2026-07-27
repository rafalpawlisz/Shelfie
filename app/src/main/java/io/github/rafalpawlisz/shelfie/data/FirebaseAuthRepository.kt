package io.github.rafalpawlisz.shelfie.data

import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirebaseAuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AuthRepository {

    override fun observeUser(): Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser?.toAuthUser())
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override suspend fun ensureSignedIn(): AuthUser {
        auth.currentUser?.let { return it.toAuthUser() }
        return auth.signInAnonymously().await().requireUser()
    }

    override suspend fun linkOrSignInWithGoogle(idToken: String): SignInResult {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val anonymous = auth.currentUser?.takeIf { it.isAnonymous }
        if (anonymous != null) {
            try {
                val linked = anonymous.linkWithCredential(credential).await().requireUser()
                return SignInResult(linked, LinkOutcome.LINKED)
            } catch (_: FirebaseAuthUserCollisionException) {
                // This Google account already has an account in the project —
                // the same person's other device, or a reinstall after an
                // earlier sign-in. Taking that identity over is what the user
                // means by "this is me"; the anonymous one is the disposable
                // half of the pair. Falls through to a plain sign-in.
            }
        }
        val user = auth.signInWithCredential(credential).await().requireUser()
        return SignInResult(user, LinkOutcome.SWITCHED)
    }

    override suspend fun signInWithEmail(email: String, password: String): SignInResult {
        val user = auth.signInWithEmailAndPassword(email, password).await().requireUser()
        return SignInResult(user, LinkOutcome.SWITCHED)
    }

    override fun signOut() {
        auth.signOut()
    }

    private fun AuthResult.requireUser(): AuthUser =
        checkNotNull(user) { "Firebase reported a successful sign-in without a user" }
            .toAuthUser()

    private fun FirebaseUser.toAuthUser() = AuthUser(
        uid = uid,
        displayName = displayName,
        email = email,
        isAnonymous = isAnonymous,
    )
}
