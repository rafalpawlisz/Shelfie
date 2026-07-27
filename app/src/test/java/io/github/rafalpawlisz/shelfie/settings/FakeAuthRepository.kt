package io.github.rafalpawlisz.shelfie.settings

import io.github.rafalpawlisz.shelfie.data.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAuthRepository(
    initialUid: String? = null,
) : AuthRepository {

    private val current = MutableStateFlow(initialUid)

    /** Raised by the anonymous sign-in when set, standing in for no network. */
    var failure: Exception? = null

    var anonymousSignIns = 0
        private set

    override fun observeUid(): Flow<String?> = current

    override suspend fun ensureSignedIn(): String {
        current.value?.let { return it }
        failure?.let { throw it }
        anonymousSignIns++
        return "anon-$anonymousSignIns".also { current.value = it }
    }
}
