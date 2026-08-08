package io.github.rafalpawlisz.shelfie.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.rafalpawlisz.shelfie.data.local.ProductEntity
import io.github.rafalpawlisz.shelfie.data.local.ShelfieDatabase
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pull-side write path against a real database — the constraint behaviour
 * it depends on (unique item slots, foreign keys) only exists in SQLite, so a
 * fake store cannot prove any of this.
 */
@RunWith(AndroidJUnit4::class)
class RoomSyncLocalStoreTest {

    private lateinit var db: ShelfieDatabase
    private lateinit var store: RoomSyncLocalStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShelfieDatabase::class.java,
        ).build()
        store = RoomSyncLocalStore(
            productDao = db.productDao(),
            shoppingListDao = db.shoppingListDao(),
            barcodeDao = db.productBarcodeDao(),
            suggestionDao = db.oneOffSuggestionDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedProductAndList() {
        db.productDao().upsert(
            ProductEntity(
                id = "p1",
                name = "Milk",
                quantity = 0,
                unit = null,
                updatedAt = 1,
                createdAt = 1,
            ),
        )
        db.shoppingListDao().upsertList(
            ShoppingListEntity(
                id = "l1",
                name = "Lidl",
                createdAt = 1,
                updatedAt = 1,
                position = 1.0,
            ),
        )
    }

    private fun itemDoc(updatedAt: Long) = mapOf(
        "listId" to "l1",
        "productId" to "p1",
        "amount" to null,
        "note" to null,
        "checkedAt" to null,
        "createdAt" to 0L,
        "updatedAt" to updatedAt,
    )

    private suspend fun insertLocalItem(id: String, updatedAt: Long) {
        db.shoppingListDao().upsertItem(
            ShoppingListItemEntity(
                id = id,
                listId = "l1",
                productId = "p1",
                name = null,
                amount = null,
                note = null,
                checkedAt = null,
                createdAt = 0,
                updatedAt = updatedAt,
            ),
        )
    }

    @Test
    fun newerRemoteItemTakesOverTheSlotFromAnotherDevicesItem() = runTest {
        // Both devices added the same product to the same list, each with its
        // own id; the unique (listId, productId) index makes them collide.
        seedProductAndList()
        insertLocalItem("local", updatedAt = 100)

        val result = store.upsert(SyncCollection.ITEMS, "remote", itemDoc(updatedAt = 200))

        assertEquals(UpsertResult.APPLIED, result)
        assertNull("the losing row must be gone", db.shoppingListDao().getById("local"))
        assertEquals("remote", db.shoppingListDao().findByProduct("l1", "p1")?.id)
    }

    @Test
    fun olderRemoteItemLosesTheSlot() = runTest {
        seedProductAndList()
        insertLocalItem("local", updatedAt = 300)

        val result = store.upsert(SyncCollection.ITEMS, "remote", itemDoc(updatedAt = 200))

        assertEquals(UpsertResult.SKIPPED_OLDER, result)
        assertEquals("local", db.shoppingListDao().findByProduct("l1", "p1")?.id)
    }

    @Test
    fun equalTimestampsAreBrokenByIdSoEveryDevicePicksTheSameSurvivor() = runTest {
        seedProductAndList()
        insertLocalItem("zzz", updatedAt = 100)

        // "aaa" < "zzz", so the incoming document wins the tie.
        val result = store.upsert(SyncCollection.ITEMS, "aaa", itemDoc(updatedAt = 100))

        assertEquals(UpsertResult.APPLIED, result)
        assertEquals("aaa", db.shoppingListDao().findByProduct("l1", "p1")?.id)
    }

    @Test
    fun anItemWhoseProductIsMissingIsReportedAsMissingParent() = runTest {
        // Only the list exists: the FK on productId fails, and that is the one
        // failure worth parking and retrying.
        db.shoppingListDao().upsertList(
            ShoppingListEntity(
                id = "l1",
                name = "Lidl",
                createdAt = 1,
                updatedAt = 1,
                position = 1.0,
            ),
        )

        val result = store.upsert(SyncCollection.ITEMS, "i1", itemDoc(updatedAt = 1))

        assertEquals(UpsertResult.MISSING_PARENT, result)
    }

    @Test
    fun aMalformedDocumentIsDroppedRatherThanParked() = runTest {
        seedProductAndList()

        val result = store.upsert(SyncCollection.ITEMS, "i1", mapOf("updatedAt" to 1L))

        assertEquals(UpsertResult.MALFORMED, result)
    }
}
