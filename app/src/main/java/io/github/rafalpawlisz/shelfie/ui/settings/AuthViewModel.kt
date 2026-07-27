package io.github.rafalpawlisz.shelfie.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.FirebaseNetworkException
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.ShelfieApplication
import io.github.rafalpawlisz.shelfie.data.AuthRepository
import io.github.rafalpawlisz.shelfie.data.HouseholdRepository
import io.github.rafalpawlisz.shelfie.data.InvalidInviteCodeException
import io.github.rafalpawlisz.shelfie.data.sync.SyncStateStore
import io.github.rafalpawlisz.shelfie.data.sync.SyncStatus
import io.github.rafalpawlisz.shelfie.model.Household
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The household half of the settings screen. There is no account half: the
 * install signs itself in anonymously and the invite code does the rest, so
 * every action here starts by making sure a uid exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val householdRepository: HouseholdRepository,
    private val syncState: SyncStateStore,
    val syncStatus: StateFlow<SyncStatus>,
) : ViewModel() {

    /** The current household; null while this device is not in one. */
    val household: StateFlow<Household?> = authRepository.observeUid()
        .flatMapLatest { uid ->
            if (uid == null) flowOf(null) else householdRepository.observeHousehold(uid)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    // Failures show INSIDE the settings dialog — a snackbar would render
    // behind its scrim. Cleared when a new attempt starts and when the dialog
    // closes.
    private val _errorMessage = MutableStateFlow<Int?>(null)
    val errorMessage: StateFlow<Int?> = _errorMessage

    /**
     * Invite code of the household this device last belonged to. Shown when
     * there is no current household, so whoever left can see the way back
     * instead of being told a code exists.
     */
    private val _rememberedInviteCode = MutableStateFlow(syncState.lastHouseholdInviteCode)
    val rememberedInviteCode: StateFlow<String?> = _rememberedInviteCode

    init {
        // The invite code is only visible from inside the household, so it has
        // to be captured while we are there — after leaving there is nothing
        // left to read it from.
        viewModelScope.launch {
            household.collect { current ->
                val code = current?.inviteCode ?: return@collect
                syncState.lastHouseholdInviteCode = code
                _rememberedInviteCode.value = code
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun createHousehold(name: String) {
        clearError()
        viewModelScope.launch {
            try {
                householdRepository.createHousehold(currentUid(), name.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Household creation failed", e)
                _errorMessage.value = errorMessageFor(e)
            }
        }
    }

    fun renameHousehold(name: String) {
        val householdId = household.value?.id ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == household.value?.name) return
        clearError()
        viewModelScope.launch {
            try {
                householdRepository.renameHousehold(householdId, trimmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Household rename failed", e)
                _errorMessage.value = errorMessageFor(e)
            }
        }
    }

    /**
     * The UI confirms leaving before calling this, and only offers
     * [deleteHousehold] to a sole member — the rules refuse it for anyone else.
     *
     * Either way the identity goes with the household: an anonymous account
     * outside one owns nothing and cannot be signed back into. Local data
     * stays, so the device keeps its pantry and simply stops syncing.
     */
    fun leaveHousehold(deleteHousehold: Boolean = false) {
        clearError()
        viewModelScope.launch {
            try {
                val uid = currentUid()
                if (deleteHousehold) {
                    householdRepository.deleteHousehold(uid)
                    // The code now names nothing; offering it as the way back
                    // would be a lie.
                    syncState.lastHouseholdInviteCode = null
                    _rememberedInviteCode.value = null
                } else {
                    householdRepository.leaveHousehold(uid)
                }
                forgetIdentity()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Leaving household failed", e)
                _errorMessage.value = errorMessageFor(e)
            }
        }
    }

    /**
     * Failing to delete the account is not worth reporting: the household part
     * already succeeded, the user is out, and all that survives is an unusable
     * account in the project.
     */
    private suspend fun forgetIdentity() {
        try {
            authRepository.deleteAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AuthViewModel", "deleting the anonymous account failed", e)
        }
    }

    /** The UI confirms switching households before calling this. */
    fun joinHousehold(code: String) {
        clearError()
        viewModelScope.launch {
            try {
                householdRepository.joinHousehold(currentUid(), code)
            } catch (e: InvalidInviteCodeException) {
                _errorMessage.value = R.string.join_invalid_code
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AuthViewModel", "Household join failed", e)
                _errorMessage.value = errorMessageFor(e)
            }
        }
    }

    /**
     * "No network" and "something broke" demand different reactions — retry
     * later versus tell someone — and a household action can be the first
     * thing on a fresh install that ever touches the network, since that is
     * when the anonymous account gets created.
     */
    private fun errorMessageFor(e: Exception): Int = when (e) {
        is FirebaseNetworkException -> R.string.error_network
        else -> R.string.household_error
    }

    /**
     * The uid to act as, signing in anonymously if this install has no
     * identity yet — normal on a first launch, and the reason nothing here is
     * gated behind a sign-in screen.
     *
     * Deliberately asked for every time rather than cached in a StateFlow: a
     * WhileSubscribed flow that nothing collects reports null forever, and the
     * first version of this read one, which made leaving a household a no-op.
     * The repository answers from memory when a session already exists.
     */
    private suspend fun currentUid(): String = authRepository.ensureSignedIn()

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
