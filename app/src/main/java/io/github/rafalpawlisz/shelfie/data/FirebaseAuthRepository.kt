package io.github.rafalpawlisz.shelfie.data

import com.google.firebase.auth.FirebaseAuth
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

    override suspend fun signInWithGoogleIdToken(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).await()
    }

    override suspend fun signInWithEmail(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
    }

    override fun signOut() {
        auth.signOut()
    }

    private fun FirebaseUser.toAuthUser() = AuthUser(
        uid = uid,
        displayName = displayName,
        email = email,
    )
}
