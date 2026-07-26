package io.github.rafalpawlisz.shelfie.data.sync

import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * One emission per Firestore snapshot: the full collection state plus the
 * delta. [docs] includes locally-pending writes (they mirror Room anyway);
 * [removedIds] is the only trustworthy deletion signal — absence from a
 * later snapshot is never used to delete, initial reconcile aside.
 */
data class RemoteSnapshot(
    val docs: List<RemoteDoc>,
    val upserts: List<RemoteDoc>,
    val removedIds: List<String>,
    // True while the emission is served from the local cache only. The
    // initial reconcile must wait for a server-confirmed snapshot — deleting
    // local rows based on an incomplete cache would destroy fresh data.
    val fromCache: Boolean,
)

interface RemoteSource {
    fun snapshots(householdId: String, collection: SyncCollection): Flow<RemoteSnapshot>
}

class FirestoreRemoteSource(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : RemoteSource {

    override fun snapshots(
        householdId: String,
        collection: SyncCollection,
    ): Flow<RemoteSnapshot> = callbackFlow {
        val registration = db.collection("households").document(householdId)
            .collection(collection.path)
            // MetadataChanges.INCLUDE is load-bearing: when the server state
            // matches the cache, the cache→server confirmation is a
            // metadata-only change and a default listener never fires it —
            // the session would wait for its server-confirmed initial
            // snapshot forever on an idle household.
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    android.util.Log.w("SyncEngine", "listen ${collection.path} failed", error)
                }
                if (snapshot == null) return@addSnapshotListener
                val upserts = mutableListOf<RemoteDoc>()
                val removed = mutableListOf<String>()
                for (change in snapshot.documentChanges) {
                    when (change.type) {
                        DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED ->
                            upserts += RemoteDoc(change.document.id, change.document.data)
                        DocumentChange.Type.REMOVED -> removed += change.document.id
                    }
                }
                trySend(
                    RemoteSnapshot(
                        docs = snapshot.documents.mapNotNull { doc ->
                            doc.data?.let { RemoteDoc(doc.id, it) }
                        },
                        upserts = upserts,
                        removedIds = removed,
                        fromCache = snapshot.metadata.isFromCache,
                    ),
                )
            }
        awaitClose { registration.remove() }
    }
}
