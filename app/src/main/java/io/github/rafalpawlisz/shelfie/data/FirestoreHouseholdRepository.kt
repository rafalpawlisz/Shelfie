package io.github.rafalpawlisz.shelfie.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.WriteBatch
import io.github.rafalpawlisz.shelfie.model.Household
import java.security.SecureRandom
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirestoreHouseholdRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : HouseholdRepository {

    override fun observeHousehold(uid: String): Flow<Household?> = callbackFlow {
        // Two chained listeners: the user's pointer document selects which
        // household document to watch; re-pointing swaps the inner listener.
        //
        // MetadataChanges.INCLUDE + the hasPendingWrites guard matter: right
        // after createHousehold the pointer fires from the local cache before
        // the batch reaches the server — attaching to the household then hits
        // PERMISSION_DENIED (the server can't prove membership on a document
        // it doesn't have yet) and a denied listener never recovers. Waiting
        // for the server-acknowledged snapshot avoids the race.
        var householdReg: ListenerRegistration? = null
        val userReg = db.collection(USERS).document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, _ ->
                if (snap == null || snap.metadata.hasPendingWrites()) return@addSnapshotListener
                householdReg?.remove()
                householdReg = null
                val householdId = snap.getString(FIELD_HOUSEHOLD_ID)
                if (householdId == null) {
                    trySend(null)
                } else {
                    householdReg = db.collection(HOUSEHOLDS).document(householdId)
                        .addSnapshotListener { householdSnap, _ ->
                            trySend(householdSnap?.toHousehold())
                        }
                }
            }
        awaitClose {
            userReg.remove()
            householdReg?.remove()
        }
    }

    override suspend fun createHousehold(uid: String, name: String) {
        // The code doc is create-only under the rules, so a collision makes
        // the whole batch fail — retry with a fresh code.
        repeat(CODE_ATTEMPTS) { attempt ->
            val householdId = db.collection(HOUSEHOLDS).document().id
            val code = generateInviteCode()
            try {
                db.runBatch { batch ->
                    batch.set(
                        db.collection(HOUSEHOLDS).document(householdId),
                        mapOf(
                            "name" to name,
                            "members" to mapOf(uid to true),
                            "inviteCode" to code,
                            "createdAt" to FieldValue.serverTimestamp(),
                            FIELD_LAST_ACTIVE_AT to FieldValue.serverTimestamp(),
                            "createdBy" to uid,
                        ),
                    )
                    batch.set(
                        db.collection(INVITE_CODES).document(code),
                        mapOf(FIELD_HOUSEHOLD_ID to householdId),
                    )
                    batch.set(
                        db.collection(USERS).document(uid),
                        mapOf(FIELD_HOUSEHOLD_ID to householdId),
                    )
                }.await()
                return
            } catch (e: Exception) {
                if (attempt == CODE_ATTEMPTS - 1) throw e
            }
        }
    }

    override suspend fun joinHousehold(uid: String, code: String) {
        val normalized = code.trim().uppercase()
        val codeSnap = db.collection(INVITE_CODES).document(normalized).get().await()
        val targetId = codeSnap.getString(FIELD_HOUSEHOLD_ID)
            ?: throw InvalidInviteCodeException()

        val userSnap = db.collection(USERS).document(uid).get().await()
        val currentId = userSnap.getString(FIELD_HOUSEHOLD_ID)
        if (currentId == targetId) return
        val old: DocumentSnapshot? = currentId
            ?.let { db.collection(HOUSEHOLDS).document(it).get().await() }
            ?.takeIf { it.exists() }

        db.runBatch { batch ->
            batch.update(
                db.collection(HOUSEHOLDS).document(targetId),
                "members.$uid",
                true,
            )
            batch.set(
                db.collection(USERS).document(uid),
                mapOf(FIELD_HOUSEHOLD_ID to targetId),
            )
            if (old != null) batch.leave(old, uid)
        }.await()
    }

    override suspend fun leaveHousehold(uid: String) {
        val userSnap = db.collection(USERS).document(uid).get().await()
        val currentId = userSnap.getString(FIELD_HOUSEHOLD_ID) ?: return
        val current = db.collection(HOUSEHOLDS).document(currentId).get().await()

        db.runBatch { batch ->
            // Drop the pointer even if the household document is already gone
            // (e.g. the other member deleted it) — otherwise the user is stuck
            // pointing at nothing with no way out.
            batch.update(
                db.collection(USERS).document(uid),
                FIELD_HOUSEHOLD_ID,
                FieldValue.delete(),
            )
            if (current.exists()) batch.leave(current, uid)
        }.await()
    }

    override suspend fun markHouseholdActive(householdId: String) {
        // Server time on purpose: device clocks drift (a cloned emulator was
        // half an hour off), and this value exists to be compared across
        // households and read by a human much later.
        db.collection(HOUSEHOLDS).document(householdId)
            .update(FIELD_LAST_ACTIVE_AT, FieldValue.serverTimestamp())
            .await()
    }

    /**
     * Removes [uid] from [household]'s members — the only mutation the rules
     * let a member make to themselves.
     *
     * A household emptied this way is deliberately NOT deleted: it keeps its
     * invite code, and joining is allowed for non-members, so anyone holding
     * the code can come back to the data later. That makes "everyone left by
     * accident" recoverable, at the price of empty households lingering.
     * Deleting them instead would strand the pantry subcollections as
     * unreadable orphans anyway (Firestore has no cascade).
     */
    private fun WriteBatch.leave(household: DocumentSnapshot, uid: String) {
        update(
            db.collection(HOUSEHOLDS).document(household.id),
            "members.$uid",
            FieldValue.delete(),
        )
    }

    private fun DocumentSnapshot.toHousehold(): Household? {
        if (!exists()) return null
        val members = get("members") as? Map<*, *> ?: return null
        return Household(
            id = id,
            name = getString("name").orEmpty(),
            inviteCode = getString("inviteCode").orEmpty(),
            memberIds = members.keys.filterIsInstance<String>().toSet(),
        )
    }

    private fun generateInviteCode(): String {
        val random = SecureRandom()
        return buildString(CODE_LENGTH) {
            repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
        }
    }

    private companion object {
        const val HOUSEHOLDS = "households"
        const val USERS = "users"
        const val INVITE_CODES = "inviteCodes"
        const val FIELD_HOUSEHOLD_ID = "householdId"
        const val FIELD_LAST_ACTIVE_AT = "lastActiveAt"

        // No 0/O/1/I — codes get read out loud between household members.
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 6
        const val CODE_ATTEMPTS = 3
    }
}
