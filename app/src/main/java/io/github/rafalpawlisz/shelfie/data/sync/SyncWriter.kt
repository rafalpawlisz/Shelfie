package io.github.rafalpawlisz.shelfie.data.sync

import com.google.firebase.firestore.FirebaseFirestore

/**
 * The only thing that touches Firestore in the push direction. Calls are
 * fire-and-forget on purpose: the SDK queues writes durably offline and
 * replays them after restarts, which is the whole outbox story.
 */
interface SyncWriter {
    fun set(householdId: String, collection: SyncCollection, docId: String, data: Map<String, Any?>)
    fun delete(householdId: String, collection: SyncCollection, docId: String)
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

    private fun doc(householdId: String, collection: SyncCollection, docId: String) =
        db.collection("households").document(householdId)
            .collection(collection.path).document(docId)
}
