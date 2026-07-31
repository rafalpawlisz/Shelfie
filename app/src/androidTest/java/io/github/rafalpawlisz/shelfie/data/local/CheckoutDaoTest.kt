package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkout against a real database: the archived-product rule lives in SQL, so
 * only SQLite can prove it.
 */
@RunWith(AndroidJUnit4::class)
class CheckoutDaoTest {

    private lateinit var db: ShelfieDatabase
    private lateinit var dao: ShoppingListDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShelfieDatabase::class.java,
        ).build()
        dao = db.shoppingListDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun product(id: String, archived: Boolean) {
        db.productDao().upsert(
            ProductEntity(
                id = id,
                name = id,
                quantity = 0,
                unit = null,
                updatedAt = 1,
                archivedAt = if (archived) 1 else null,
                createdAt = 1,
            ),
        )
    }

    private suspend fun checkedItem(id: String, productId: String, amount: Int) {
        dao.upsertItem(
            ShoppingListItemEntity(
                id = id,
                listId = "l1",
                productId = productId,
                name = null,
                amount = amount,
                note = null,
                checkedAt = 5,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private suspend fun seed() {
        dao.upsertList(
            ShoppingListEntity(id = "l1", name = "Lidl", createdAt = 1, updatedAt = 1, position = 1.0),
        )
        product("active", archived = false)
        product("archived", archived = true)
        checkedItem("i-active", "active", amount = 2)
        checkedItem("i-archived", "archived", amount = 7)
    }

    @Test
    fun checkoutBanksAndClearsOnlyItemsOfActiveProducts() = runTest {
        seed()

        dao.checkout(listId = "l1", timestamp = 10)

        // The visible item was banked and its row removed...
        assertEquals("active product should have received its amount", 2, quantityOf("active"))
        assertNull("banked row must be gone", dao.getById("i-active"))
        // ...while the hidden one is untouched: no phantom stock, and the row
        // survives so restoring the product brings it back as it was.
        assertEquals("archived product must not gain stock", 0, quantityOf("archived"))
        assertNotNull("dormant row must survive checkout", dao.getById("i-archived"))
    }

    @Test
    fun checkedItemIdsReportsExactlyWhatCheckoutRemoves() = runTest {
        seed()

        // What the repository hands to the sync layer must match the deletion.
        assertEquals(listOf("i-active"), dao.checkedItemIds("l1"))
    }

    private suspend fun quantityOf(id: String): Int =
        db.productDao().observeAllRows().first().first { it.id == id }.quantity
}
