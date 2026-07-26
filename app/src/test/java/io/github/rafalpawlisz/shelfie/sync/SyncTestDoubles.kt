package io.github.rafalpawlisz.shelfie.sync

import io.github.rafalpawlisz.shelfie.data.sync.RemoteDoc
import io.github.rafalpawlisz.shelfie.data.sync.RemoteSnapshot
import io.github.rafalpawlisz.shelfie.data.sync.RemoteSource
import io.github.rafalpawlisz.shelfie.data.sync.SyncCollection
import io.github.rafalpawlisz.shelfie.data.sync.SyncLocalStore
import io.github.rafalpawlisz.shelfie.data.sync.SyncStateStore
import io.github.rafalpawlisz.shelfie.data.sync.SyncWriter
import io.github.rafalpawlisz.shelfie.data.sync.UpsertResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * In-memory stand-in for RoomSyncLocalStore, with just enough FK awareness:
 * an ITEMS doc applies only when its productId exists in PRODUCTS and its
 * listId in LISTS (mirroring the real foreign keys).
 */
class FakeSyncLocalStore : SyncLocalStore {

    val rows = mutableMapOf<SyncCollection, MutableMap<String, Map<String, Any?>>>()
        .apply { SyncCollection.entries.forEach { put(it, mutableMapOf()) } }

    override suspend fun upsert(
        collection: SyncCollection,
        docId: String,
        data: Map<String, Any?>,
    ): UpsertResult {
        val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong()
            ?: return UpsertResult.MALFORMED
        val local = rows.getValue(collection)[docId]
        val localUpdatedAt = (local?.get("updatedAt") as? Number)?.toLong()
        if (localUpdatedAt != null && localUpdatedAt >= remoteUpdatedAt) {
            return UpsertResult.SKIPPED_OLDER
        }
        if (collection == SyncCollection.ITEMS) {
            val productExists = data["productId"] in rows.getValue(SyncCollection.PRODUCTS).keys
            val listExists = data["listId"] in rows.getValue(SyncCollection.LISTS).keys
            if (!productExists || !listExists) return UpsertResult.MISSING_PARENT
        }
        rows.getValue(collection)[docId] = data
        return UpsertResult.APPLIED
    }

    override suspend fun delete(collection: SyncCollection, docId: String) {
        rows.getValue(collection).remove(docId)
    }

    override suspend fun idsSyncedUpTo(
        collection: SyncCollection,
        syncedUpTo: Long,
    ): List<String> = rows.getValue(collection)
        .filterValues { (it["updatedAt"] as? Number)?.toLong()?.let { at -> at <= syncedUpTo } == true }
        .keys.toList()

    fun ids(collection: SyncCollection): Set<String> = rows.getValue(collection).keys.toSet()
}

class FakeSyncStateStore(
    override var lastSyncedHouseholdId: String? = null,
    override var lastSyncedAt: Long = 0,
    override var clockOffsetMillis: Long = 0,
) : SyncStateStore

class RecordingSyncWriter : SyncWriter {
    data class Write(val hid: String, val collection: SyncCollection, val docId: String)

    val sets = mutableListOf<Write>()
    val deletes = mutableListOf<Write>()

    override fun set(
        householdId: String,
        collection: SyncCollection,
        docId: String,
        data: Map<String, Any?>,
    ) {
        sets += Write(householdId, collection, docId)
    }

    override fun delete(householdId: String, collection: SyncCollection, docId: String) {
        deletes += Write(householdId, collection, docId)
    }
}

/** Scriptable remote: emit snapshots per collection at will. */
class FakeRemoteSource : RemoteSource {
    private val flows = SyncCollection.entries.associateWith {
        MutableSharedFlow<RemoteSnapshot>(replay = 1, extraBufferCapacity = 8)
    }

    override fun snapshots(householdId: String, collection: SyncCollection): Flow<RemoteSnapshot> =
        flows.getValue(collection)

    suspend fun emitInitial(
        collection: SyncCollection,
        docs: List<RemoteDoc>,
        fromCache: Boolean = false,
    ) {
        flows.getValue(collection).emit(
            RemoteSnapshot(docs = docs, upserts = docs, removedIds = emptyList(), fromCache = fromCache),
        )
    }

    suspend fun emitChange(
        collection: SyncCollection,
        allDocs: List<RemoteDoc>,
        upserts: List<RemoteDoc> = emptyList(),
        removedIds: List<String> = emptyList(),
    ) {
        flows.getValue(collection).emit(
            RemoteSnapshot(docs = allDocs, upserts = upserts, removedIds = removedIds, fromCache = false),
        )
    }
}

fun remoteProduct(id: String, name: String, updatedAt: Long): RemoteDoc = RemoteDoc(
    id,
    mapOf(
        "name" to name,
        "quantity" to 1L,
        "unit" to null,
        "minQuantity" to null,
        "notes" to null,
        "emoji" to null,
        "archivedAt" to null,
        "createdAt" to 0L,
        "updatedAt" to updatedAt,
    ),
)

fun remoteItem(id: String, listId: String, productId: String, updatedAt: Long): RemoteDoc = RemoteDoc(
    id,
    mapOf(
        "listId" to listId,
        "productId" to productId,
        "amount" to null,
        "note" to null,
        "checkedAt" to null,
        "createdAt" to 0L,
        "updatedAt" to updatedAt,
    ),
)

fun remoteList(id: String, name: String, updatedAt: Long): RemoteDoc = RemoteDoc(
    id,
    mapOf(
        "name" to name,
        "position" to 1.0,
        "archivedAt" to null,
        "createdAt" to 0L,
        "updatedAt" to updatedAt,
    ),
)
