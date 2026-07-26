package io.github.rafalpawlisz.shelfie.data.sync

/** One remote document, as seen by a snapshot listener. */
data class RemoteDoc(val id: String, val data: Map<String, Any?>)

/**
 * Applies remote snapshots to Room via [SyncLocalStore].
 *
 * Cross-collection FK timing is handled with an orphan buffer: an item can
 * arrive before the product it references (listeners are independent), so a
 * MISSING_PARENT upsert is parked and retried after every later apply; once
 * its parent lands, it goes through.
 */
class SyncApplier(private val store: SyncLocalStore) {

    private data class Orphan(val collection: SyncCollection, val doc: RemoteDoc)

    private val orphans = mutableListOf<Orphan>()

    /**
     * Full-state reconcile for a collection: LWW-upsert every remote doc and
     * delete local rows that don't exist remotely. Runs on a session's initial
     * snapshot — the only place deletions made while this device was away can
     * be noticed, since a fresh listener reports every document as ADDED and
     * never REMOVED.
     *
     * [syncedUpTo] bounds the deletions: only rows that were already part of a
     * completed sync may go. Rows written after it (offline work, or work done
     * while this session was still waiting for the server) are kept and left
     * for the push mirror. Pass [Long.MAX_VALUE] to replace local content
     * wholesale, which is what joining someone else's household means.
     */
    suspend fun reconcile(
        collection: SyncCollection,
        docs: List<RemoteDoc>,
        syncedUpTo: Long,
    ) {
        val remoteIds = docs.map { it.id }.toSet()
        for (localId in store.idsSyncedUpTo(collection, syncedUpTo)) {
            if (localId !in remoteIds) store.delete(collection, localId)
        }
        upsertAll(collection, docs)
        retryOrphans()
    }

    /** Incremental apply: upserts for added/changed docs, deletes for removed ids. */
    suspend fun apply(collection: SyncCollection, upserts: List<RemoteDoc>, removedIds: List<String>) {
        removedIds.forEach { store.delete(collection, it) }
        // A removal can also invalidate parked children (their parent is gone
        // for good) — dropping them on retry failure below handles that.
        upsertAll(collection, upserts)
        retryOrphans()
    }

    private suspend fun upsertAll(collection: SyncCollection, docs: List<RemoteDoc>) {
        for (doc in docs) {
            if (store.upsert(collection, doc.id, doc.data) == UpsertResult.MISSING_PARENT) {
                // Replace an older parked copy of the same doc.
                orphans.removeAll { it.collection == collection && it.doc.id == doc.id }
                orphans += Orphan(collection, doc)
            }
        }
    }

    private suspend fun retryOrphans() {
        // Iterate until a full pass applies nothing (children of children).
        while (true) {
            if (orphans.isEmpty()) return
            val retrying = orphans.toList()
            orphans.clear()
            var appliedAny = false
            for (orphan in retrying) {
                when (store.upsert(orphan.collection, orphan.doc.id, orphan.doc.data)) {
                    UpsertResult.MISSING_PARENT -> orphans += orphan
                    UpsertResult.APPLIED -> appliedAny = true
                    else -> Unit // resolved another way; drop it
                }
            }
            if (!appliedAny) return
        }
    }
}
