package io.github.rafalpawlisz.shelfie.sync

import io.github.rafalpawlisz.shelfie.data.sync.SyncApplier
import io.github.rafalpawlisz.shelfie.data.sync.SyncCollection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncApplierTest {

    @Test
    fun `reconcile upserts remote docs and deletes local rows absent remotely`() = runTest {
        val store = FakeSyncLocalStore()
        val applier = SyncApplier(store)
        store.upsert(SyncCollection.PRODUCTS, "stale", remoteProduct("stale", "Old", 1).data)
        store.upsert(SyncCollection.PRODUCTS, "kept", remoteProduct("kept", "Kept", 1).data)

        applier.reconcile(
            SyncCollection.PRODUCTS,
            listOf(remoteProduct("kept", "Kept", 1), remoteProduct("new", "New", 5)),
            syncedUpTo = Long.MAX_VALUE,
        )

        assertEquals(setOf("kept", "new"), store.ids(SyncCollection.PRODUCTS))
    }

    @Test
    fun `reconcile keeps local rows written after the last completed sync`() = runTest {
        val store = FakeSyncLocalStore()
        val applier = SyncApplier(store)
        // "synced" is part of the last sync; "fresh" was added afterwards
        // (offline work) and has not reached the server.
        store.upsert(SyncCollection.PRODUCTS, "synced", remoteProduct("synced", "Old", 50).data)
        store.upsert(SyncCollection.PRODUCTS, "fresh", remoteProduct("fresh", "New", 150).data)

        applier.reconcile(SyncCollection.PRODUCTS, docs = emptyList(), syncedUpTo = 100)

        assertEquals(setOf("fresh"), store.ids(SyncCollection.PRODUCTS))
    }

    @Test
    fun `older remote doc never overwrites a newer local row`() = runTest {
        val store = FakeSyncLocalStore()
        val applier = SyncApplier(store)
        store.upsert(SyncCollection.PRODUCTS, "p1", remoteProduct("p1", "Newer local", 10).data)

        applier.apply(
            SyncCollection.PRODUCTS,
            upserts = listOf(remoteProduct("p1", "Older remote", 5)),
            removedIds = emptyList(),
        )

        assertEquals(
            "Newer local",
            store.rows.getValue(SyncCollection.PRODUCTS).getValue("p1")["name"],
        )
    }

    @Test
    fun `removed ids delete local rows`() = runTest {
        val store = FakeSyncLocalStore()
        val applier = SyncApplier(store)
        store.upsert(SyncCollection.PRODUCTS, "p1", remoteProduct("p1", "Milk", 1).data)

        applier.apply(SyncCollection.PRODUCTS, upserts = emptyList(), removedIds = listOf("p1"))

        assertTrue(store.ids(SyncCollection.PRODUCTS).isEmpty())
    }

    @Test
    fun `an item arriving before its parents waits and applies once they land`() = runTest {
        val store = FakeSyncLocalStore()
        val applier = SyncApplier(store)

        // Item first: both parents missing.
        applier.apply(
            SyncCollection.ITEMS,
            upserts = listOf(remoteItem("i1", "l1", "p1", 3)),
            removedIds = emptyList(),
        )
        assertTrue(store.ids(SyncCollection.ITEMS).isEmpty())

        // Product lands — list still missing, item keeps waiting.
        applier.apply(
            SyncCollection.PRODUCTS,
            upserts = listOf(remoteProduct("p1", "Milk", 1)),
            removedIds = emptyList(),
        )
        assertTrue(store.ids(SyncCollection.ITEMS).isEmpty())

        // List lands — the parked item goes through on the retry pass.
        applier.apply(
            SyncCollection.LISTS,
            upserts = listOf(remoteList("l1", "Lidl", 1)),
            removedIds = emptyList(),
        )
        assertEquals(setOf("i1"), store.ids(SyncCollection.ITEMS))
    }

    @Test
    fun `a parked orphan deleted remotely does not come back when its parent lands`() = runTest {
        val store = FakeSyncLocalStore()
        val applier = SyncApplier(store)
        applier.apply(
            SyncCollection.ITEMS,
            upserts = listOf(remoteItem("i1", "l1", "p1", 3)),
            removedIds = emptyList(),
        )

        // Deleted remotely while still waiting for its parents.
        applier.apply(SyncCollection.ITEMS, upserts = emptyList(), removedIds = listOf("i1"))

        applier.apply(SyncCollection.PRODUCTS, listOf(remoteProduct("p1", "Milk", 1)), emptyList())
        applier.apply(SyncCollection.LISTS, listOf(remoteList("l1", "Lidl", 1)), emptyList())

        assertTrue(store.ids(SyncCollection.ITEMS).isEmpty())
    }

    @Test
    fun `reset drops orphans so they cannot leak into another household`() = runTest {
        val store = FakeSyncLocalStore()
        val applier = SyncApplier(store)
        applier.apply(
            SyncCollection.ITEMS,
            upserts = listOf(remoteItem("i1", "l1", "p1", 3)),
            removedIds = emptyList(),
        )

        applier.reset()

        // The parents arrive in the next household's session; nothing may apply.
        applier.apply(SyncCollection.PRODUCTS, listOf(remoteProduct("p1", "Milk", 1)), emptyList())
        applier.apply(SyncCollection.LISTS, listOf(remoteList("l1", "Lidl", 1)), emptyList())
        assertTrue(store.ids(SyncCollection.ITEMS).isEmpty())
    }

    @Test
    fun `a newer copy of a parked orphan replaces the older one`() = runTest {
        val store = FakeSyncLocalStore()
        val applier = SyncApplier(store)

        applier.apply(
            SyncCollection.ITEMS,
            upserts = listOf(remoteItem("i1", "l1", "p1", 3)),
            removedIds = emptyList(),
        )
        applier.apply(
            SyncCollection.ITEMS,
            upserts = listOf(remoteItem("i1", "l1", "p1", 7)),
            removedIds = emptyList(),
        )
        applier.apply(
            SyncCollection.PRODUCTS,
            upserts = listOf(remoteProduct("p1", "Milk", 1)),
            removedIds = emptyList(),
        )
        applier.apply(
            SyncCollection.LISTS,
            upserts = listOf(remoteList("l1", "Lidl", 1)),
            removedIds = emptyList(),
        )

        assertEquals(
            7L,
            (store.rows.getValue(SyncCollection.ITEMS).getValue("i1")["updatedAt"] as Number).toLong(),
        )
    }
}
