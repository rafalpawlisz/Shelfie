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
import io.github.rafalpawlisz.shelfie.data.LinkOutcome
import io.github.rafalpawlisz.shelfie.data.SignInResult
import io.github.rafalpawlisz.shelfie.data.sync.SyncStateStore
import io.github.rafalpawlisz.shelfie.data.sync.SyncStatus
import io.github.rafalpawlisz.shelfie.model.Household
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val householdRepository: HouseholdRepository,
    private val syncState: SyncStateStore,
    val syncStatus: StateFlow<SyncStatus>,
) : ViewModel() {

    val user: StateFlow<AuthUser?> = authRepository.observeUser().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    /** The current user's household; null while they are not in one. */
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
     * Attach a Google account to this install: Credential Manager picks the
     * account (needs an Activity context for its UI), then the token goes
     * through [signInWithGoogleToken].
     */
    fun signIn(activityContext: Context) {
        clearSettingsError()
        viewModelScope.launch {
            val idToken = requestGoogleIdToken(activityContext) ?: return@launch
            authenticateAndSettle { authRepository.linkOrSignInWithGoogle(idToken) }
        }
    }

    /**
     * The device-independent half of the Google flow. Public because that
     * boundary is where the interesting behaviour lives — linking versus
     * taking over an account — and Credential Manager cannot run off-device.
     */
    fun signInWithGoogleToken(idToken: String) {
        clearSettingsError()
        viewModelScope.launch {
            authenticateAndSettle { authRepository.linkOrSignInWithGoogle(idToken) }
        }
    }

    fun signInWithEmail(email: String, password: String) {
        clearSettingsError()
        viewModelScope.launch {
            authenticateAndSettle { authRepository.signInWithEmail(email.trim(), password) }
        }
    }

    /** Null on cancellation (not an error) or a failure already reported. */
    private suspend fun requestGoogleIdToken(activityContext: Context): String? {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(activityContext.getString(R.string.default_web_client_id))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        try {
            val credential = CredentialManager.create(activityContext)
                .getCredential(activityContext, request)
                .credential
            if (
                credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return GoogleIdTokenCredential.createFrom(credential.data).idToken
            }
            _settingsError.value = R.string.sign_in_failed
        } catch (_: GetCredentialCancellationException) {
            // The user backed out of the account picker — not an error.
        } catch (e: GetCredentialException) {
            Log.w("AuthViewModel", "Google sign-in failed", e)
            _settingsError.value = R.string.sign_in_failed
        }
        return null
    }

    private suspend fun authenticateAndSettle(authenticate: suspend () -> SignInResult) {
        try {
            rememberHouseholdForRecovery()
            settle(authenticate())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AuthViewModel", "Sign-in failed", e)
            _settingsError.value = signInErrorMessage(e)
        }
    }

    /**
     * Persist the invite code before touching the identity, because a switch
     * makes the household unreachable — and therefore its code invisible —
     * until we have rejoined it. See [SyncStateStore.lastHouseholdInviteCode].
     */
    private fun rememberHouseholdForRecovery() {
        household.value?.let { syncState.lastHouseholdInviteCode = it.inviteCode }
    }

    /**
     * Finish a sign-in. Linking keeps the uid, so the household comes along by
     * itself; taking over an existing identity does not, and that identity has
     * to be put back into the household by code.
     */
    private suspend fun settle(result: SignInResult) {
        if (result.outcome == LinkOutcome.LINKED) return
        // The new identity's own household wins. The common case for a switch
        // is the same person's second device, which is already in one, and
        // joining over it would be a switch nobody asked for.
        if (householdRepository.observeHousehold(result.user.uid).first() != null) return
        val code = syncState.lastHouseholdInviteCode ?: return
        try {
            householdRepository.joinHousehold(result.user.uid, code)
        } catch (e: InvalidInviteCodeException) {
            // The household is gone (deleted while we were away). The sign-in
            // itself succeeded, so say what failed, not that everything did.
            Log.w("AuthViewModel", "household recovery found no household for the code", e)
            _settingsError.value = R.string.link_household_lost
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

    /** Household actions can now be the first thing that ever needs an identity. */
    private fun householdErrorMessage(e: Exception): Int = when (e) {
        is FirebaseNetworkException -> R.string.sign_in_error_network
        else -> R.string.household_error
    }

    fun signOut() {
        authRepository.signOut()
    }

    fun createHousehold(name: String) {
        clearSettingsError()
        viewModelScope.launch {
            try {
                householdRepository.createHousehold(currentUid(), name.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Household creation failed", e)
                _settingsError.value = householdErrorMessage(e)
            }
        }
    }

    fun renameHousehold(name: String) {
        val householdId = household.value?.id ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == household.value?.name) return
        clearSettingsError()
        viewModelScope.launch {
            try {
                householdRepository.renameHousehold(householdId, trimmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Household rename failed", e)
                _settingsError.value = R.string.household_error
            }
        }
    }

    /** The UI confirms leaving before calling this. */
    fun leaveHousehold() {
        val uid = user.value?.uid ?: return
        clearSettingsError()
        viewModelScope.launch {
            try {
                householdRepository.leaveHousehold(uid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Leaving household failed", e)
                _settingsError.value = R.string.household_error
            }
        }
    }

    /** The UI confirms switching households before calling this. */
    fun joinHousehold(code: String) {
        clearSettingsError()
        viewModelScope.launch {
            try {
                householdRepository.joinHousehold(currentUid(), code)
            } catch (e: InvalidInviteCodeException) {
                _settingsError.value = R.string.join_invalid_code
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Household join failed", e)
                _settingsError.value = householdErrorMessage(e)
            }
        }
    }

    /**
     * The uid to act as, signing in anonymously if this install has no
     * identity yet — which is the normal state on a first launch, and the
     * reason nothing here is gated behind a sign-in screen.
     */
    private suspend fun currentUid(): String =
        user.value?.uid ?: authRepository.ensureSignedIn().uid

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as ShelfieApplication
                AuthViewModel(
                    app.container.authRepository,
                    app.container.householdRepository,
                    app.container.syncStateStore,
                    app.container.syncEngine.status,
                )
            }
        }
    }
}
