package io.github.rafalpawlisz.shelfie.sync

import io.github.rafalpawlisz.shelfie.MainDispatcherRule
import io.github.rafalpawlisz.shelfie.data.local.ProductEntity
import io.github.rafalpawlisz.shelfie.data.local.OneOffSuggestionEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductBarcodeEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductListOrderEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemEntity
import io.github.rafalpawlisz.shelfie.data.sync.DiffSyncEngine
import io.github.rafalpawlisz.shelfie.data.sync.SyncApplier
import io.github.rafalpawlisz.shelfie.data.sync.SyncCollection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiffSyncEngineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class Harness(scope: TestScope) {
        val householdIds = MutableStateFlow<String?>(null)
        val products = MutableStateFlow<List<ProductEntity>>(emptyList())
        val writer = RecordingSyncWriter()
        val remote = FakeRemoteSource()
        val store = FakeSyncLocalStore()
        val syncState = FakeSyncStateStore()
        val engine = DiffSyncEngine(
            householdIds = householdIds,
            products = products,
            lists = MutableStateFlow<List<ShoppingListEntity>>(emptyList()),
            items = MutableStateFlow<List<ShoppingListItemEntity>>(emptyList()),
            listOrders = MutableStateFlow<List<ProductListOrderEntity>>(emptyList()),
            barcodes = MutableStateFlow<List<ProductBarcodeEntity>>(emptyList()),
            oneOffSuggestions = MutableStateFlow<List<OneOffSuggestionEntity>>(emptyList()),
            writer = writer,
            remote = remote,
            applier = SyncApplier(store),
            syncState = syncState,
            scope = CoroutineScope(
                scope.backgroundScope.coroutineContext +
                    UnconfinedTestDispatcher(scope.testScheduler),
            ),
        )

        init {
            engine.start()
        }

        /**
         * Every collection reports the given remote state (empty by default).
         *
         * Driven off the enum rather than a hand-written list: a session only
         * becomes ready once each collection has delivered a first snapshot, so
         * a collection added later and forgotten here would hang every test in
         * this class with no hint as to why.
         */
        suspend fun emitInitials(products: List<io.github.rafalpawlisz.shelfie.data.sync.RemoteDoc> = emptyList()) {
            for (collection in SyncCollection.entries) {
                remote.emitInitial(
                    collection,
                    if (collection == SyncCollection.PRODUCTS) products else emptyList(),
                )
            }
        }
    }

    private fun product(id: String, name: String, updatedAt: Long) = ProductEntity(
        id = id,
        name = name,
        quantity = 1,
        unit = null,
        updatedAt = updatedAt,
        archivedAt = null,
        createdAt = 0,
        minQuantity = null,
        notes = null,
        emoji = null,
    )

    @Test
    fun `no household means no writes`() = runTest {
        val h = Harness(this)
        h.products.value = listOf(product("p1", "Milk", 1))
        runCurrent()

        assertTrue(h.writer.sets.isEmpty())
    }

    @Test
    fun `empty remote household is seeded with the local snapshot`() = runTest {
        val h = Harness(this)
        h.products.value = listOf(product("p1", "Milk", 1), product("p2", "Bread", 1))
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials()
        runCurrent()

        assertEquals(setOf("p1", "p2"), h.writer.sets.map { it.docId }.toSet())
        // Local rows survive: no reconcile against the empty remote.
        h.store.upsert(SyncCollection.PRODUCTS, "p1", remoteProduct("p1", "Milk", 1).data)
        assertEquals(setOf("p1"), h.store.ids(SyncCollection.PRODUCTS))
    }

    @Test
    fun `non-empty remote reconciles local rows before pushing`() = runTest {
        val h = Harness(this)
        // A stale local-store row that the remote doesn't have.
        h.store.upsert(SyncCollection.PRODUCTS, "stale", remoteProduct("stale", "Old", 1).data)
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials(products = listOf(remoteProduct("remote1", "Cloud milk", 5)))
        runCurrent()

        assertEquals(setOf("remote1"), h.store.ids(SyncCollection.PRODUCTS))
    }

    @Test
    fun `rows written while the session waits for the server survive the reconcile`() = runTest {
        // The offline data-loss case: a known household, the session parked on
        // its initial server snapshot (indefinitely when offline), and the user
        // keeps adding rows. They are absent remotely but must not be deleted.
        val h = Harness(this)
        h.syncState.lastSyncedHouseholdId = "h1"
        h.syncState.lastSyncedAt = 100
        h.store.upsert(SyncCollection.PRODUCTS, "old", remoteProduct("old", "Synced", 50).data)
        h.householdIds.value = "h1"
        runCurrent()

        // Written after the last completed sync, while no server snapshot has
        // arrived yet. In the app this is one Room row; the harness splits Room
        // into the pull-side store and the push-side flow, so set both.
        h.store.upsert(SyncCollection.PRODUCTS, "offline", remoteProduct("offline", "Fresh", 500).data)
        h.products.value = listOf(product("offline", "Fresh", 500))
        h.emitInitials(products = listOf(remoteProduct("remote", "Cloud", 200)))
        runCurrent()

        val ids = h.store.ids(SyncCollection.PRODUCTS)
        assertTrue("offline row was deleted by the reconcile", "offline" in ids)
        assertTrue("remote row was not pulled", "remote" in ids)
        // The stale synced row is gone: absent remotely means deleted elsewhere.
        assertTrue("stale synced row survived", "old" !in ids)
        // ...and the surviving local row gets pushed.
        assertTrue(h.writer.sets.any { it.docId == "offline" })
    }

    @Test
    fun `a first session with a household still replaces local rows wholesale`() = runTest {
        // Joining someone else's household: no lastSyncedHouseholdId match, so
        // even freshly written local rows go — that is what the join dialog
        // warns about.
        val h = Harness(this)
        h.store.upsert(SyncCollection.PRODUCTS, "mine", remoteProduct("mine", "Local", 999).data)
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials(products = listOf(remoteProduct("theirs", "Household", 5)))
        runCurrent()

        assertEquals(setOf("theirs"), h.store.ids(SyncCollection.PRODUCTS))
    }

    @Test
    fun `a household this device created never replaces local rows`() = runTest {
        // createHousehold claims the household in sync state before anyone can
        // observe it, with lastSyncedAt = 0. That is what separates "I made
        // this" from "I am joining yours" — and it has to hold even when the
        // remote side already has documents, which is what happens when the
        // creating batch was queued offline and lands much later while the
        // pantry has moved on.
        val h = Harness(this)
        h.syncState.lastSyncedHouseholdId = "h1"
        h.syncState.lastSyncedAt = 0
        h.store.upsert(SyncCollection.PRODUCTS, "added", remoteProduct("added", "Bread", 300).data)
        h.products.value = listOf(product("added", "Bread", 300))
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials(products = listOf(remoteProduct("seeded", "Milk", 100)))
        runCurrent()

        val ids = h.store.ids(SyncCollection.PRODUCTS)
        assertTrue("a row added before the session was deleted", "added" in ids)
        assertTrue("the household's own row was not pulled", "seeded" in ids)
        assertTrue(h.writer.sets.any { it.docId == "added" })
    }

    @Test
    fun `the household becomes known even when its remote side is empty`() = runTest {
        // Creating a household seeds it from local data; the next session must
        // not treat that household as someone else's and wipe local content.
        val h = Harness(this)
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials()
        runCurrent()

        assertEquals("h1", h.syncState.lastSyncedHouseholdId)
        assertTrue(h.syncState.lastSyncedAt > 0)
    }

    @Test
    fun `remote removals delete local rows mid-session`() = runTest {
        val h = Harness(this)
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials(products = listOf(remoteProduct("p1", "Milk", 5)))
        runCurrent()
        assertEquals(setOf("p1"), h.store.ids(SyncCollection.PRODUCTS))

        h.remote.emitChange(
            SyncCollection.PRODUCTS,
            allDocs = emptyList(),
            removedIds = listOf("p1"),
        )
        runCurrent()

        assertTrue(h.store.ids(SyncCollection.PRODUCTS).isEmpty())
    }

    @Test
    fun `only changed local rows are re-pushed`() = runTest {
        val h = Harness(this)
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials()
        runCurrent()
        h.products.value = listOf(product("p1", "Milk", 1), product("p2", "Bread", 1))
        runCurrent()
        h.writer.sets.clear()

        h.products.value = listOf(product("p1", "Milk", 2), product("p2", "Bread", 1))
        runCurrent()

        assertEquals(listOf("p1"), h.writer.sets.map { it.docId })
    }

    @Test
    fun `onDeleted forwards to the writer under the active household`() = runTest {
        val h = Harness(this)
        h.householdIds.value = "h1"
        runCurrent()

        h.engine.onDeleted(SyncCollection.ITEMS, listOf("i1", "i2"))

        assertEquals(listOf("i1", "i2"), h.writer.deletes.map { it.docId })
    }

    @Test
    fun `deletions reported before a session exists are flushed when one starts`() = runTest {
        // Auth restore and the household lookup take a moment after launch; a
        // deletion in that window used to be dropped, and the surviving remote
        // document brought the row back on the first pull.
        val h = Harness(this)

        h.engine.onDeleted(SyncCollection.ITEMS, listOf("i1", "i2"))
        assertTrue("nothing can be written without a household", h.writer.deletes.isEmpty())

        h.householdIds.value = "h1"
        runCurrent()

        assertEquals(listOf("i1", "i2"), h.writer.deletes.map { it.docId })
    }

    @Test
    fun `a snapshot arriving before the session finished starting is still applied`() = runTest {
        // Snapshots used to be shared with replay = 1, which dropped everything
        // emitted while the session waited for its first server snapshot — a
        // lost REMOVED left the row in place, and the push mirror then undid
        // another device's deletion.
        val h = Harness(this)
        h.householdIds.value = "h1"
        runCurrent()

        // Products has its initial snapshot; the other collections do not yet,
        // so the session is still starting up.
        h.remote.emitInitial(SyncCollection.PRODUCTS, listOf(remoteProduct("p1", "Milk", 5)))
        runCurrent()
        // A delta lands during that gap, and another snapshot follows it.
        h.remote.emitChange(SyncCollection.PRODUCTS, allDocs = emptyList(), removedIds = listOf("p1"))
        h.remote.emitChange(SyncCollection.PRODUCTS, allDocs = emptyList())
        runCurrent()

        // The rest of the initial snapshots arrive and the session proceeds.
        h.remote.emitInitial(SyncCollection.LISTS, emptyList())
        h.remote.emitInitial(SyncCollection.ITEMS, emptyList())
        h.remote.emitInitial(SyncCollection.LIST_ORDER, emptyList())
        h.remote.emitInitial(SyncCollection.BARCODES, emptyList())
        runCurrent()

        assertTrue(
            "the removal that landed mid-startup was dropped",
            h.store.ids(SyncCollection.PRODUCTS).isEmpty(),
        )
    }
}
