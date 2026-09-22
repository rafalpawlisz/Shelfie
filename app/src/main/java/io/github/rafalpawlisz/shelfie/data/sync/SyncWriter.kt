package io.github.rafalpawlisz.shelfie.data.sync

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * The only thing that touches Firestore in the push direction. Calls are
 * fire-and-forget on purpose: the SDK queues writes durably offline and
 * replays them after restarts, which is the whole outbox story.
 */
interface SyncWriter {
    fun set(householdId: String, collection: SyncCollection, docId: String, data: Map<String, Any?>)
    fun delete(householdId: String, collection: SyncCollection, docId: String)

    /**
     * Suspends until the server has acknowledged every write handed over so
     * far; returns at once when the queue is empty. The queue is the SDK's, so
     * this is the only evidence that a write is no longer merely queued — the
     * thing the reconcile's deletion arm turns on (see
     * [SyncStateStore.lastSyncedAt]).
     */
    suspend fun awaitPendingWrites()
}

class FirestoreSyncWriter(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : SyncWriter {

    override fun set(
        householdId: String,
        collection: SyncCollection,
        docId: String,
        data: Map<String, Any?>,
    ) {
        doc(householdId, collection, docId).set(data)
    }

    override fun delete(householdId: String, collection: SyncCollection, docId: String) {
        doc(householdId, collection, docId).delete()
    }

    override suspend fun awaitPendingWrites() {
        db.waitForPendingWrites().await()
    }

    private fun doc(householdId: String, collection: SyncCollection, docId: String) =
        db.collection("households").document(householdId)
            .collection(collection.path).document(docId)
}
