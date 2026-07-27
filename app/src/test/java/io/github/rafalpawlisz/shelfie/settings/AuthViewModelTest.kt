package io.github.rafalpawlisz.shelfie.settings

import com.google.firebase.FirebaseNetworkException
import io.github.rafalpawlisz.shelfie.MainDispatcherRule
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.data.AuthUser
import io.github.rafalpawlisz.shelfie.data.LinkOutcome
import io.github.rafalpawlisz.shelfie.data.sync.SyncStatus
import io.github.rafalpawlisz.shelfie.sync.FakeSyncStateStore
import io.github.rafalpawlisz.shelfie.ui.settings.AuthViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val anonymous = AuthUser(
        uid = "anon-1",
        displayName = null,
        email = null,
        isAnonymous = true,
    )

    private fun makeViewModel(
        auth: FakeAuthRepository,
        households: FakeHouseholdRepository,
        syncState: FakeSyncStateStore = FakeSyncStateStore(),
    ) = AuthViewModel(
        auth,
        households,
        syncState,
        MutableStateFlow<SyncStatus>(SyncStatus.Off),
    )

    /** The state flows are WhileSubscribed, so nothing is live until observed. */
    private fun TestScope.observe(viewModel: AuthViewModel): CoroutineScope =
        backgroundScope.also { scope ->
            scope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.user.collect {} }
            scope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.household.collect {} }
        }

    @Test
    fun `creating a household on a fresh install signs in anonymously first`() = runTest {
        val auth = FakeAuthRepository(initialUser = null)
        val households = FakeHouseholdRepository()
        val viewModel = makeViewModel(auth, households)
        observe(viewModel)

        viewModel.createHousehold("Dom")

        assertEquals(1, auth.anonymousSignIns)
        assertEquals("Dom", viewModel.household.value?.name)
        assertEquals(setOf("anon-1"), viewModel.household.value?.memberIds)
    }

    @Test
    fun `no network while creating a household says so instead of a generic error`() = runTest {
        val auth = FakeAuthRepository(initialUser = null)
        auth.failure = FirebaseNetworkException("offline")
        val viewModel = makeViewModel(auth, FakeHouseholdRepository())
        observe(viewModel)

        viewModel.createHousehold("Dom")

        assertEquals(R.string.sign_in_error_network, viewModel.householdError.value)
        assertNull(viewModel.household.value)
    }

    @Test
    fun `linking Google keeps the uid and the household`() = runTest {
        val auth = FakeAuthRepository(initialUser = anonymous)
        val households = FakeHouseholdRepository()
        households.seed(name = "Dom", code = "ABC123", members = setOf("anon-1"))
        val viewModel = makeViewModel(auth, households)
        observe(viewModel)
        auth.googleOutcome = LinkOutcome.LINKED

        viewModel.signInWithGoogleToken("token")

        assertEquals("anon-1", viewModel.user.value?.uid)
        assertEquals(false, viewModel.user.value?.isAnonymous)
        assertEquals("Dom", viewModel.household.value?.name)
        // Nothing to recover, so the household is never re-joined.
        assertTrue(households.joins.isEmpty())
        assertNull(viewModel.accountError.value)
    }

    @Test
    fun `taking over an existing Google account rejoins the household by code`() = runTest {
        val auth = FakeAuthRepository(initialUser = anonymous)
        val households = FakeHouseholdRepository()
        households.seed(name = "Dom", code = "ABC123", members = setOf("anon-1"))
        val syncState = FakeSyncStateStore()
        val viewModel = makeViewModel(auth, households, syncState)
        observe(viewModel)
        auth.googleOutcome = LinkOutcome.SWITCHED

        viewModel.signInWithGoogleToken("token")

        assertEquals("google-uid", viewModel.user.value?.uid)
        assertEquals(listOf("google-uid" to "ABC123"), households.joins)
        assertEquals("Dom", viewModel.household.value?.name)
        assertEquals("ABC123", syncState.lastHouseholdInviteCode)
        assertNull(viewModel.accountError.value)
    }

    @Test
    fun `an identity that already has a household keeps it`() = runTest {
        val auth = FakeAuthRepository(initialUser = anonymous)
        val households = FakeHouseholdRepository()
        households.seed(name = "Solo", code = "ABC123", members = setOf("anon-1"))
        // The same person's other phone is already in this one.
        households.seed(name = "Wspólne", code = "XYZ789", members = setOf("google-uid"))
        val viewModel = makeViewModel(auth, households)
        observe(viewModel)
        auth.googleOutcome = LinkOutcome.SWITCHED

        viewModel.signInWithGoogleToken("token")

        assertEquals("Wspólne", viewModel.household.value?.name)
        assertTrue(households.joins.isEmpty())
    }

    @Test
    fun `signing in with no household to recover joins nothing`() = runTest {
        val auth = FakeAuthRepository(initialUser = anonymous)
        val households = FakeHouseholdRepository()
        val viewModel = makeViewModel(auth, households)
        observe(viewModel)
        auth.googleOutcome = LinkOutcome.SWITCHED

        viewModel.signInWithGoogleToken("token")

        assertTrue(households.joins.isEmpty())
        assertNull(viewModel.household.value)
        assertNull(viewModel.accountError.value)
    }

    @Test
    fun `a remembered code whose household is gone reports the loss`() = runTest {
        // What a crash between the identity switch and the re-join leaves
        // behind: a code in the store, no household to match it.
        val auth = FakeAuthRepository(initialUser = anonymous)
        val households = FakeHouseholdRepository()
        val syncState = FakeSyncStateStore(lastHouseholdInviteCode = "GONE12")
        val viewModel = makeViewModel(auth, households, syncState)
        observe(viewModel)
        auth.googleOutcome = LinkOutcome.SWITCHED

        viewModel.signInWithGoogleToken("token")

        assertEquals(R.string.link_household_lost, viewModel.householdError.value)
        // The sign-in itself worked, so nothing is reported against it.
        assertNull(viewModel.accountError.value)
        assertEquals("google-uid", viewModel.user.value?.uid)
    }

    @Test
    fun `the last household's code survives leaving it`() = runTest {
        val auth = FakeAuthRepository(initialUser = anonymous)
        val households = FakeHouseholdRepository()
        households.seed(name = "Dom", code = "ABC123", members = setOf("anon-1"))
        val syncState = FakeSyncStateStore()
        val viewModel = makeViewModel(auth, households, syncState)
        observe(viewModel)

        viewModel.leaveHousehold()

        assertNull(viewModel.household.value)
        // The only remaining trace of the code, and the way back in.
        assertEquals("ABC123", viewModel.rememberedInviteCode.value)
        assertEquals("ABC123", syncState.lastHouseholdInviteCode)
    }

    @Test
    fun `email sign-in also recovers the household`() = runTest {
        val auth = FakeAuthRepository(initialUser = anonymous)
        val households = FakeHouseholdRepository()
        households.seed(name = "Dom", code = "ABC123", members = setOf("anon-1"))
        val viewModel = makeViewModel(auth, households)
        observe(viewModel)

        viewModel.signInWithEmail("test@shelfie.local", "secret")

        assertEquals("email-uid", viewModel.user.value?.uid)
        assertEquals(listOf("email-uid" to "ABC123"), households.joins)
        assertEquals("Dom", viewModel.household.value?.name)
    }

    @Test
    fun `joining with an unknown code reports it and changes nothing`() = runTest {
        val auth = FakeAuthRepository(initialUser = anonymous)
        val households = FakeHouseholdRepository()
        val viewModel = makeViewModel(auth, households)
        observe(viewModel)

        viewModel.joinHousehold("NOPE12")

        assertEquals(R.string.join_invalid_code, viewModel.householdError.value)
        assertNull(viewModel.household.value)
    }
}
