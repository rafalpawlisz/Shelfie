package io.github.rafalpawlisz.shelfie.sync

import io.github.rafalpawlisz.shelfie.MainDispatcherRule
import io.github.rafalpawlisz.shelfie.data.local.ProductEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemEntity
import io.github.rafalpawlisz.shelfie.data.sync.DiffSyncEngine
import io.github.rafalpawlisz.shelfie.data.sync.SyncCollection
import io.github.rafalpawlisz.shelfie.data.sync.SyncWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiffSyncEngineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class RecordingWriter : SyncWriter {
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

    private class Harness(scope: TestScope) {
        val householdIds = MutableStateFlow<String?>(null)
        val products = MutableStateFlow<List<ProductEntity>>(emptyList())
        val items = MutableStateFlow<List<ShoppingListItemEntity>>(emptyList())
        val writer = RecordingWriter()
        val engine = DiffSyncEngine(
            householdIds = householdIds,
            products = products,
            lists = MutableStateFlow(emptyList()),
            items = items,
            listOrders = MutableStateFlow(emptyList()),
            barcodes = MutableStateFlow(emptyList()),
            writer = writer,
            scope = scope.backgroundScope(),
        )

        init {
            engine.start()
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

        assertTrue(h.writer.sets.isEmpty())
    }

    @Test
    fun `first snapshot after a household appears pushes everything`() = runTest {
        val h = Harness(this)
        h.products.value = listOf(product("p1", "Milk", 1), product("p2", "Bread", 1))

        h.householdIds.value = "h1"

        assertEquals(
            setOf("p1", "p2"),
            h.writer.sets.map { it.docId }.toSet(),
        )
        assertTrue(h.writer.sets.all { it.hid == "h1" && it.collection == SyncCollection.PRODUCTS })
    }

    @Test
    fun `only changed rows are re-pushed`() = runTest {
        val h = Harness(this)
        h.householdIds.value = "h1"
        h.products.value = listOf(product("p1", "Milk", 1), product("p2", "Bread", 1))
        h.writer.sets.clear()

        // p1 changes, p2 stays identical.
        h.products.value = listOf(product("p1", "Milk", 2), product("p2", "Bread", 1))

        assertEquals(listOf("p1"), h.writer.sets.map { it.docId })
    }

    @Test
    fun `a row that vanishes and comes back identical is pushed again`() = runTest {
        val h = Harness(this)
        h.householdIds.value = "h1"
        val row = product("p1", "Milk", 1)
        h.products.value = listOf(row)
        h.writer.sets.clear()

        h.products.value = emptyList() // deletion mirrored via onDeleted, not here
        h.products.value = listOf(row)

        assertEquals(listOf("p1"), h.writer.sets.map { it.docId })
    }

    @Test
    fun `switching household re-pushes the full snapshot to the new one`() = runTest {
        val h = Harness(this)
        h.householdIds.value = "h1"
        h.products.value = listOf(product("p1", "Milk", 1))
        h.writer.sets.clear()

        h.householdIds.value = "h2"

        assertEquals(listOf("h2"), h.writer.sets.map { it.hid })
        assertEquals(listOf("p1"), h.writer.sets.map { it.docId })
    }

    @Test
    fun `onDeleted forwards to the writer under the active household`() = runTest {
        val h = Harness(this)
        h.householdIds.value = "h1"

        h.engine.onDeleted(SyncCollection.ITEMS, listOf("i1", "i2"))

        assertEquals(listOf("i1", "i2"), h.writer.deletes.map { it.docId })
        assertTrue(h.writer.deletes.all { it.hid == "h1" && it.collection == SyncCollection.ITEMS })
    }

    @Test
    fun `onDeleted without a household is a no-op`() = runTest {
        val h = Harness(this)

        h.engine.onDeleted(SyncCollection.ITEMS, listOf("i1"))

        assertTrue(h.writer.deletes.isEmpty())
    }
}

// Engine coroutines must die with the test — run them on the backgroundScope
// with an unconfined dispatcher so emissions are processed eagerly.
private fun TestScope.backgroundScope() =
    kotlinx.coroutines.CoroutineScope(
        backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler),
    )
