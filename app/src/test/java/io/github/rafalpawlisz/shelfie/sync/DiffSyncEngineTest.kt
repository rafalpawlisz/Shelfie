package io.github.rafalpawlisz.shelfie.sync

import io.github.rafalpawlisz.shelfie.MainDispatcherRule
import io.github.rafalpawlisz.shelfie.data.local.ProductEntity
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
        val engine = DiffSyncEngine(
            householdIds = householdIds,
            products = products,
            lists = MutableStateFlow<List<ShoppingListEntity>>(emptyList()),
            items = MutableStateFlow<List<ShoppingListItemEntity>>(emptyList()),
            listOrders = MutableStateFlow<List<ProductListOrderEntity>>(emptyList()),
            barcodes = MutableStateFlow<List<ProductBarcodeEntity>>(emptyList()),
            writer = writer,
            remote = remote,
            applier = SyncApplier(store),
            scope = CoroutineScope(
                scope.backgroundScope.coroutineContext +
                    UnconfinedTestDispatcher(scope.testScheduler),
            ),
        )

        init {
            engine.start()
        }

        /** All five collections report the given remote state (empty by default). */
        suspend fun emitInitials(products: List<io.github.rafalpawlisz.shelfie.data.sync.RemoteDoc> = emptyList()) {
            remote.emitInitial(SyncCollection.PRODUCTS, products)
            remote.emitInitial(SyncCollection.LISTS, emptyList())
            remote.emitInitial(SyncCollection.ITEMS, emptyList())
            remote.emitInitial(SyncCollection.LIST_ORDER, emptyList())
            remote.emitInitial(SyncCollection.BARCODES, emptyList())
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
    fun `onDeleted without a household is a no-op`() = runTest {
        val h = Harness(this)
        h.engine.onDeleted(SyncCollection.ITEMS, listOf("i1"))

        assertTrue(h.writer.deletes.isEmpty())
    }
}
