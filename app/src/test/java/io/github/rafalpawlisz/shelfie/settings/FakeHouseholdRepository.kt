package io.github.rafalpawlisz.shelfie.settings

import io.github.rafalpawlisz.shelfie.data.HouseholdRepository
import io.github.rafalpawlisz.shelfie.data.InvalidInviteCodeException
import io.github.rafalpawlisz.shelfie.model.Household
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

class FakeHouseholdRepository : HouseholdRepository {

    private val households = MutableStateFlow<List<Household>>(emptyList())

    // users/{uid}.householdId, the pointer the real repository keeps.
    private val pointers = MutableStateFlow<Map<String, String>>(emptyMap())

    private var nextId = 1

    val joins = mutableListOf<Pair<String, String>>()

    /** Seed a household that already exists remotely, e.g. on another device. */
    fun seed(name: String, code: String, members: Set<String>): Household {
        val household = Household(
            id = "household-${nextId++}",
            name = name,
            inviteCode = code,
            memberIds = members,
        )
        households.update { it + household }
        pointers.update { it + members.associateWith { _ -> household.id } }
        return household
    }

    // Both sources matter: the pointer says which household, the list says
    // what it currently looks like (a rename has to reach observers too).
    override fun observeHousehold(uid: String): Flow<Household?> =
        combine(pointers, households) { map, all -> all.firstOrNull { it.id == map[uid] } }

    override suspend fun createHousehold(uid: String, name: String) {
        seed(name = name, code = "CODE${nextId}", members = setOf(uid))
    }

    override suspend fun joinHousehold(uid: String, code: String) {
        joins += uid to code
        val target = households.value.firstOrNull { it.inviteCode == code }
            ?: throw InvalidInviteCodeException()
        households.update { list ->
            list.map { household ->
                when (household.id) {
                    // Joining is also a switch: leave whatever we were in.
                    target.id -> household.copy(memberIds = household.memberIds + uid)
                    else -> household.copy(memberIds = household.memberIds - uid)
                }
            }
        }
        pointers.update { it + (uid to target.id) }
    }

    override suspend fun leaveHousehold(uid: String) {
        households.update { list ->
            list.map { it.copy(memberIds = it.memberIds - uid) }
        }
        pointers.update { it - uid }
    }

    override suspend fun renameHousehold(householdId: String, name: String) {
        households.update { list ->
            list.map { if (it.id == householdId) it.copy(name = name) else it }
        }
    }

    override suspend fun markHouseholdActive(householdId: String): Long? = null
}
