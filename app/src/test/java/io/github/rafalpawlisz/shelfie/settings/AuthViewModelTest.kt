package io.github.rafalpawlisz.shelfie.settings

import com.google.firebase.FirebaseNetworkException
import io.github.rafalpawlisz.shelfie.MainDispatcherRule
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.data.sync.SyncStatus
import io.github.rafalpawlisz.shelfie.sync.FakeSyncStateStore
import io.github.rafalpawlisz.shelfie.ui.settings.AuthViewModel
import io.github.rafalpawlisz.shelfie.ui.settings.ErrorSpot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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

    /** household is WhileSubscribed, so nothing is live until observed. */
    private fun TestScope.observe(viewModel: AuthViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.household.collect {}
        }
    }

    @Test
    fun `creating a household on a fresh install signs in anonymously first`() = runTest {
        val auth = FakeAuthRepository(initialUid = null)
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
        val auth = FakeAuthRepository(initialUid = null)
        auth.failure = FirebaseNetworkException("offline")
        val viewModel = makeViewModel(auth, FakeHouseholdRepository())
        observe(viewModel)

        viewModel.createHousehold("Dom")

        assertEquals(R.string.error_network, viewModel.error.value?.message)
        assertEquals(ErrorSpot.CREATE, viewModel.error.value?.spot)
        assertNull(viewModel.household.value)
    }

    @Test
    fun `joining moves the device into the household`() = runTest {
        val auth = FakeAuthRepository(initialUid = "anon-1")
        val households = FakeHouseholdRepository()
        households.seed(name = "Dom", code = "ABC123", members = setOf("uid-other"))
        val viewModel = makeViewModel(auth, households)
        observe(viewModel)

        viewModel.joinHousehold("ABC123")

        assertEquals("Dom", viewModel.household.value?.name)
        assertEquals(setOf("uid-other", "anon-1"), viewModel.household.value?.memberIds)
        assertNull(viewModel.error.value)
    }

    @Test
    fun `joining with an unknown code reports it and changes nothing`() = runTest {
        val auth = FakeAuthRepository(initialUid = "anon-1")
        val viewModel = makeViewModel(auth, FakeHouseholdRepository())
        observe(viewModel)

        viewModel.joinHousehold("NOPE12")

        assertEquals(R.string.join_invalid_code, viewModel.error.value?.message)
        // Placement is behaviour here: the section is tall, and this message
        // belongs at the code field rather than above the create-household form.
        assertEquals(ErrorSpot.JOIN, viewModel.error.value?.spot)
        assertNull(viewModel.household.value)
    }

    @Test
    fun `the last household's code survives leaving it`() = runTest {
        val auth = FakeAuthRepository(initialUid = "anon-1")
        val households = FakeHouseholdRepository()
        households.seed(name = "Dom", code = "ABC123", members = setOf("anon-1"))
        val syncState = FakeSyncStateStore()
        val viewModel = makeViewModel(auth, households, syncState)
        observe(viewModel)

        viewModel.leaveHousehold()

        assertNull(viewModel.household.value)
        // Without an account this is the only remaining way back in, so it has
        // to outlive the membership that made it visible.
        assertEquals("ABC123", viewModel.rememberedInviteCode.value)
        assertEquals("ABC123", syncState.lastHouseholdInviteCode)
    }

    @Test
    fun `leaving discards the identity but keeps the household`() = runTest {
        val auth = FakeAuthRepository(initialUid = "anon-1")
        val households = FakeHouseholdRepository()
        households.seed(name = "Dom", code = "ABC123", members = setOf("anon-1", "uid-other"))
        val viewModel = makeViewModel(auth, households)
        observe(viewModel)

        viewModel.leaveHousehold()

        assertEquals(listOf("anon-1"), auth.deletedAccounts)
        // The household is the other member's now, and it keeps its data.
        assertEquals(setOf("uid-other"), households.membersOf("Dom"))
    }

    @Test
    fun `leaving and deleting takes the household and the code with it`() = runTest {
        val auth = FakeAuthRepository(initialUid = "anon-1")
        val households = FakeHouseholdRepository()
        households.seed(name = "Dom", code = "ABC123", members = setOf("anon-1"))
        val syncState = FakeSyncStateStore()
        val viewModel = makeViewModel(auth, households, syncState)
        observe(viewModel)

        viewModel.leaveHousehold(deleteHousehold = true)

        assertNull(viewModel.household.value)
        assertNull(households.findByName("Dom"))
        assertEquals(listOf("anon-1"), auth.deletedAccounts)
        // Offering the code as the way back would be a lie now.
        assertNull(viewModel.rememberedInviteCode.value)
        assertNull(syncState.lastHouseholdInviteCode)
    }

    @Test
    fun `a household that outlives its account deletion is still left`() = runTest {
        val auth = FakeAuthRepository(initialUid = "anon-1")
        auth.deleteFailure = FirebaseNetworkException("offline")
        val households = FakeHouseholdRepository()
        households.seed(name = "Dom", code = "ABC123", members = setOf("anon-1"))
        val viewModel = makeViewModel(auth, households)
        observe(viewModel)

        viewModel.leaveHousehold()

        // The leave succeeded; a leftover account is not worth an error the
        // user can do nothing about.
        assertNull(viewModel.household.value)
        assertNull(viewModel.error.value)
    }
}
