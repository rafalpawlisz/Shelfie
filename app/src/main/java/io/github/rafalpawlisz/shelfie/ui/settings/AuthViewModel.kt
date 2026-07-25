package io.github.rafalpawlisz.shelfie.ui.settings

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.ShelfieApplication
import io.github.rafalpawlisz.shelfie.data.AuthRepository
import io.github.rafalpawlisz.shelfie.data.AuthUser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    val user: StateFlow<AuthUser?> = authRepository.observeUser().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    // One-shot sign-in failures (string resource ids) for a snackbar; user
    // cancellation is deliberately not an error.
    private val errorChannel = Channel<Int>(Channel.BUFFERED)
    val errors = errorChannel.receiveAsFlow()

    /**
     * Full Google sign-in round trip: Credential Manager picks the account
     * (needs an Activity context for its UI), then the ID token is exchanged
     * for a Firebase session. State updates arrive through [user].
     */
    fun signIn(activityContext: Context) {
        viewModelScope.launch {
            try {
                val option = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(
                        activityContext.getString(R.string.default_web_client_id),
                    )
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build()
                val credential = CredentialManager.create(activityContext)
                    .getCredential(activityContext, request)
                    .credential
                if (
                    credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    authRepository.signInWithGoogleIdToken(idToken)
                } else {
                    errorChannel.send(R.string.sign_in_failed)
                }
            } catch (_: GetCredentialCancellationException) {
                // The user backed out of the account picker — not an error.
            } catch (e: GetCredentialException) {
                Log.w("AuthViewModel", "Google sign-in failed", e)
                errorChannel.send(R.string.sign_in_failed)
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Firebase sign-in failed", e)
                errorChannel.send(R.string.sign_in_failed)
            }
        }
    }

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            try {
                authRepository.signInWithEmail(email.trim(), password)
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Email sign-in failed", e)
                errorChannel.send(R.string.sign_in_failed)
            }
        }
    }

    fun signOut() {
        authRepository.signOut()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as ShelfieApplication
                AuthViewModel(app.container.authRepository)
            }
        }
    }
}
