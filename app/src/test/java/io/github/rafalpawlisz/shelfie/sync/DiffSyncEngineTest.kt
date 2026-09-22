package io.github.rafalpawlisz.shelfie.sync

import io.github.rafalpawlisz.shelfie.MainDispatcherRule
import io.github.rafalpawlisz.shelfie.data.local.ProductEntity
import io.github.rafalpawlisz.shelfie.data.local.OneOffSuggestionEntity
import io.github.rafalpawlisz.shelfie.data.sync.RemoteDoc
import io.github.rafalpawlisz.shelfie.data.sync.toSyncDoc
import io.github.rafalpawlisz.shelfie.data.local.ProductBarcodeEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductListOrderEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemEntity
import io.github.rafalpawlisz.shelfie.data.sync.DiffSyncEngine
import io.github.rafalpawlisz.shelfie.data.sync.SyncApplier
import io.github.rafalpawlisz.shelfie.data.sync.SyncClock
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
        val items = MutableStateFlow<List<ShoppingListItemEntity>>(emptyList())
        val writer = RecordingSyncWriter()
        val remote = FakeRemoteSource()
        val store = FakeSyncLocalStore()
        val syncState = FakeSyncStateStore()

        /**
         * Test-controlled clock. The reconcile turns on which side of
         * [FakeSyncStateStore.lastSyncedAt] a row's updatedAt falls, so a test
         * that asserts about that line has to be able to place those
         * timestamps deliberately — the wall clock places them all on the same
         * side and proves nothing.
         */
        var now = 10_000L

        val engine = DiffSyncEngine(
            householdIds = householdIds,
            products = products,
            lists = MutableStateFlow<List<ShoppingListEntity>>(emptyList()),
            items = items,
            listOrders = MutableStateFlow<List<ProductListOrderEntity>>(emptyList()),
            barcodes = MutableStateFlow<List<ProductBarcodeEntity>>(emptyList()),
            oneOffSuggestions = MutableStateFlow<List<OneOffSuggestionEntity>>(emptyList()),
            writer = writer,
            remote = remote,
            applier = SyncApplier(store),
            syncState = syncState,
            clock = SyncClock { now },
            scope = CoroutineScope(
                scope.backgroundScope.coroutineContext +
                    UnconfinedTestDispatcher(scope.testScheduler),
            ),
        )

        init {
            // Room is one table for both directions, so a row the applier
            // deletes has to leave the push-side flow too — otherwise a mirror
            // would push a row Room no longer has.
            store.onDelete = { collection, docId ->
                when (collection) {
                    SyncCollection.ITEMS ->
                        items.value = items.value.filterNot { it.id == docId }

                    SyncCollection.PRODUCTS ->
                        products.value = products.value.filterNot { it.id == docId }

                    else -> Unit
                }
            }
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
        suspend fun emitInitials(
            products: List<RemoteDoc> = emptyList(),
            lists: List<RemoteDoc> = emptyList(),
            items: List<RemoteDoc> = emptyList(),
        ) {
            for (collection in SyncCollection.entries) {
                remote.emitInitial(
                    collection,
                    when (collection) {
                        SyncCollection.PRODUCTS -> products
                        SyncCollection.LISTS -> lists
                        SyncCollection.ITEMS -> items
                        else -> emptyList()
                    },
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

    private fun item(id: String, updatedAt: Long) = ShoppingListItemEntity(
        id = id,
        listId = "l1",
        productId = "p1",
        name = null,
        amount = 2,
        unit = null,
        position = null,
        note = null,
        checkedAt = null,
        createdAt = 0,
        updatedAt = updatedAt,
    )

    /**
     * The entity as the server would hand it back: Firestore has no 32-bit
     * integer, so every Int returns a Long. A seed built without that detour
     * would compare equal for the wrong reason and prove nothing.
     */
    private fun asServerReturnedIt(id: String, doc: Map<String, Any?>) = RemoteDoc(
        id,
        doc.mapValues { (_, value) -> if (value is Int) value.toLong() else value },
    )

    @Test
    fun `a session start does not re-push what the server already has`() = runTest {
        // The resurrection bug, in its smallest form. The push diff started
        // empty every session, so the first Room emission wrote every local row
        // back — including rows another device had just deleted.
        val h = Harness(this)
        h.items.value = listOf(item("i1", updatedAt = 5))
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials(items = listOf(asServerReturnedIt("i1", item("i1", 5).toSyncDoc())))
        runCurrent()

        assertTrue(
            "nothing to say: the server has this row already, got ${h.writer.sets}",
            h.writer.sets.none { it.collection == SyncCollection.ITEMS },
        )
    }

    @Test
    fun `a row deleted on another device stays deleted`() = runTest {
        // The reported case, end to end. A finishes shopping and closes the app
        // before the deletions reach the server; B opens the app while the rows
        // are still there, then the deletion arrives. B must not write them back
        // — that is what put the finished shopping back on A's phone.
        val h = Harness(this)
        h.items.value = listOf(item("i1", updatedAt = 5))
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials(items = listOf(asServerReturnedIt("i1", item("i1", 5).toSyncDoc())))
        runCurrent()

        // A's deletion lands.
        h.remote.emitChange(SyncCollection.ITEMS, allDocs = emptyList(), removedIds = listOf("i1"))
        runCurrent()
        // Room reacts to the applied deletion: the row leaves the local flow.
        h.items.value = emptyList()
        runCurrent()

        assertTrue(
            "the deleted row was written back: ${h.writer.sets}",
            h.writer.sets.none { it.collection == SyncCollection.ITEMS },
        )
    }

    @Test
    fun `a row pulled from another device is not written back once that device finishes shopping`() = runTest {
        // The same finished shopping trip as above, reported a second time —
        // this is what the seeded push cache did not cover. B pulled the rows
        // the other phone checked off, so B's copies carry that phone's
        // updatedAt: newer than where B's last session started. The reconcile
        // read them as B's own work not yet pushed, and once the other phone
        // finished shopping the mirror wrote them back for the whole household.
        val h = Harness(this)
        h.syncState.lastSyncedHouseholdId = "h1"
        h.syncState.lastSyncedAt = 100
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials(products = listOf(remoteProduct("p1", "Milk", 50)), lists = listOf(remoteList("l1", "Shopping", 50)))
        runCurrent()

        // The other phone checks the item off; here it arrives as a pull.
        h.now = 20_000
        val checked = remoteItem("i1", "l1", "p1", updatedAt = 20_000)
        h.remote.emitChange(
            SyncCollection.ITEMS,
            allDocs = listOf(checked),
            upserts = listOf(checked),
        )
        h.items.value = listOf(item("i1", updatedAt = 20_000))
        runCurrent()
        h.writer.sets.clear()

        // That phone finishes shopping and closes the app; B was not there to
        // hear it, so all B learns is the server state it finds on returning.
        h.now = 30_000
        h.householdIds.value = null
        runCurrent()
        h.remote.emitInitial(SyncCollection.ITEMS, emptyList())
        h.householdIds.value = "h1"
        runCurrent()

        assertTrue(
            "the row the other phone deleted is still here: " +
                "${h.store.ids(SyncCollection.ITEMS)}",
            h.store.ids(SyncCollection.ITEMS).isEmpty(),
        )
        assertTrue(
            "the deleted row was written back: ${h.writer.sets}",
            h.writer.sets.none { it.collection == SyncCollection.ITEMS },
        )
    }

    @Test
    fun `a row written during an outage stops being protected once the server acknowledges it`() = runTest {
        // The line has to sit where the server last confirmed the queue, not
        // where the wait for that confirmation began. Checking things off with
        // no signal piles rows up while a settle is already in flight; a mark
        // taken at the wait's start would leave all of them on the protected
        // side of the line, and a checkout on the other phone would be undone
        // here the moment they came back into range.
        val h = Harness(this)
        h.syncState.lastSyncedHouseholdId = "h1"
        h.syncState.lastSyncedAt = 100
        h.writer.acknowledged.value = false
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials(
            products = listOf(remoteProduct("p1", "Milk", 50)),
            lists = listOf(remoteList("l1", "Shopping", 50)),
        )
        runCurrent()

        // No signal: the item is checked off and joins the queue.
        h.now = 20_000
        h.store.upsert(SyncCollection.ITEMS, "i1", remoteItem("i1", "l1", "p1", 20_000).data)
        h.items.value = listOf(item("i1", updatedAt = 20_000))
        runCurrent()
        assertTrue(
            "the row never reached the queue: ${h.writer.sets}",
            h.writer.sets.any { it.docId == "i1" },
        )
        h.writer.sets.clear()

        // Back in range: the queue drains.
        h.now = 30_000
        h.writer.acknowledged.value = true
        runCurrent()

        // ...and the other phone, done shopping, deletes the row meanwhile.
        h.householdIds.value = null
        runCurrent()
        h.remote.emitInitial(SyncCollection.ITEMS, emptyList())
        h.now = 40_000
        h.householdIds.value = "h1"
        runCurrent()

        assertTrue(
            "the row the other phone deleted is still here: " +
                "${h.store.ids(SyncCollection.ITEMS)}",
            h.store.ids(SyncCollection.ITEMS).isEmpty(),
        )
        assertTrue(
            "the deleted row was written back: ${h.writer.sets}",
            h.writer.sets.none { it.collection == SyncCollection.ITEMS },
        )
    }

    @Test
    fun `a row the server does not have is still pushed`() = runTest {
        // The other half of the seeding: it must not turn into "never push".
        val h = Harness(this)
        h.items.value = listOf(item("i2", updatedAt = 7))
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials(items = listOf(asServerReturnedIt("i1", item("i1", 5).toSyncDoc())))
        runCurrent()

        assertEquals(
            listOf("i2"),
            h.writer.sets.filter { it.collection == SyncCollection.ITEMS }.map { it.docId },
        )
    }

    @Test
    fun `an edit to a row the server has is pushed`() = runTest {
        // And the seed must not freeze a row: changing it locally still writes.
        val h = Harness(this)
        h.items.value = listOf(item("i1", updatedAt = 5))
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials(items = listOf(asServerReturnedIt("i1", item("i1", 5).toSyncDoc())))
        runCurrent()

        h.items.value = listOf(item("i1", updatedAt = 9))
        runCurrent()

        assertEquals(
            listOf("i1"),
            h.writer.sets.filter { it.collection == SyncCollection.ITEMS }.map { it.docId },
        )
    }

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
    fun `a row written here survives the reconcile while the server has not acknowledged it`() = runTest {
        // That protection has to come from proof of landing, never from a
        // schedule. Rows written while the queue is unacknowledged are the
        // offline case: a session that never got its writes out would otherwise
        // have them deleted as "absent remotely" at the next start.
        val h = Harness(this)
        h.syncState.lastSyncedHouseholdId = "h1"
        h.syncState.lastSyncedAt = 100
        h.writer.acknowledged.value = false
        h.store.upsert(SyncCollection.PRODUCTS, "here", remoteProduct("here", "Local", 500).data)
        h.products.value = listOf(product("here", "Local", 500))
        h.householdIds.value = "h1"
        runCurrent()
        h.emitInitials(products = listOf(remoteProduct("cloud", "Cloud", 200)))
        runCurrent()

        assertEquals("the mark moved without an acknowledgement", 100L, h.syncState.lastSyncedAt)

        // A session later, still nothing acknowledged.
        h.householdIds.value = null
        runCurrent()
        h.householdIds.value = "h1"
        runCurrent()

        assertTrue(
            "a row the server never acknowledged was deleted: " +
                "${h.store.ids(SyncCollection.PRODUCTS)}",
            "here" in h.store.ids(SyncCollection.PRODUCTS),
        )
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
