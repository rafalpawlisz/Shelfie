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
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.ShelfieApplication
import io.github.rafalpawlisz.shelfie.data.AuthRepository
import io.github.rafalpawlisz.shelfie.data.AuthUser
import io.github.rafalpawlisz.shelfie.data.HouseholdRepository
import io.github.rafalpawlisz.shelfie.data.InvalidInviteCodeException
import io.github.rafalpawlisz.shelfie.data.sync.SyncStatus
import io.github.rafalpawlisz.shelfie.model.Household
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val householdRepository: HouseholdRepository,
    val syncStatus: StateFlow<SyncStatus>,
) : ViewModel() {

    val user: StateFlow<AuthUser?> = authRepository.observeUser().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    /** The signed-in user's household; null when signed out or not in one. */
    val household: StateFlow<Household?> = authRepository.observeUser()
        .flatMapLatest { user ->
            if (user == null) flowOf(null) else householdRepository.observeHousehold(user.uid)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    // Sign-in/household failures shown INSIDE the settings dialog — a
    // snackbar would render behind the dialog scrim. Cleared when a new
    // attempt starts and when the dialog closes. User cancellation of the
    // account picker is deliberately not an error.
    private val _settingsError = MutableStateFlow<Int?>(null)
    val settingsError: StateFlow<Int?> = _settingsError

    fun clearSettingsError() {
        _settingsError.value = null
    }

    /**
     * Full Google sign-in round trip: Credential Manager picks the account
     * (needs an Activity context for its UI), then the ID token is exchanged
     * for a Firebase session. State updates arrive through [user].
     */
    fun signIn(activityContext: Context) {
        clearSettingsError()
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
                    _settingsError.value = R.string.sign_in_failed
                }
            } catch (_: GetCredentialCancellationException) {
                // The user backed out of the account picker — not an error.
            } catch (e: GetCredentialException) {
                Log.w("AuthViewModel", "Google sign-in failed", e)
                _settingsError.value = R.string.sign_in_failed
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Firebase sign-in failed", e)
                _settingsError.value = signInErrorMessage(e)
            }
        }
    }

    fun signInWithEmail(email: String, password: String) {
        clearSettingsError()
        viewModelScope.launch {
            try {
                authRepository.signInWithEmail(email.trim(), password)
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Email sign-in failed", e)
                _settingsError.value = signInErrorMessage(e)
            }
        }
    }

    /**
     * "No network" and "wrong credentials" demand different user reactions
     * (retry later vs retype) — a generic failure message conflates them,
     * which already misled a real debugging session once.
     */
    private fun signInErrorMessage(e: Exception): Int = when (e) {
        is FirebaseNetworkException -> R.string.sign_in_error_network
        is FirebaseAuthInvalidUserException,
        is FirebaseAuthInvalidCredentialsException,
        -> R.string.sign_in_error_credentials

        else -> R.string.sign_in_failed
    }

    fun signOut() {
        authRepository.signOut()
    }

    fun createHousehold(name: String) {
        val uid = user.value?.uid ?: return
        clearSettingsError()
        viewModelScope.launch {
            try {
                householdRepository.createHousehold(uid, name.trim())
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Household creation failed", e)
                _settingsError.value = R.string.household_error
            }
        }
    }

    /** The UI confirms switching households before calling this. */
    fun joinHousehold(code: String) {
        val uid = user.value?.uid ?: return
        clearSettingsError()
        viewModelScope.launch {
            try {
                householdRepository.joinHousehold(uid, code)
            } catch (e: InvalidInviteCodeException) {
                _settingsError.value = R.string.join_invalid_code
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Household join failed", e)
                _settingsError.value = R.string.household_error
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as ShelfieApplication
                AuthViewModel(
                    app.container.authRepository,
                    app.container.householdRepository,
                    app.container.syncEngine.status,
                )
            }
        }
    }
}
