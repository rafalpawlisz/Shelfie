package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One-off items at the SQL level: rows with no product behind them. What only
 * SQLite can prove here is the unique index treating NULLs as distinct, the
 * LEFT JOIN surfacing the row's own name, and checkout removing checked
 * one-offs without touching any product's stock.
 */
@RunWith(AndroidJUnit4::class)
class OneOffItemDaoTest {

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

    private suspend fun seedList() {
        dao.upsertList(
            ShoppingListEntity(id = "l1", name = "Sklep", createdAt = 1, updatedAt = 1, position = 1.0),
        )
    }

    private fun oneOff(id: String, name: String, createdAt: Long, checkedAt: Long? = null) =
        ShoppingListItemEntity(
            id = id,
            listId = "l1",
            productId = null,
            name = name,
            amount = null,
            note = null,
            checkedAt = checkedAt,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    @Test
    fun twoOneOffsOfTheSameNameCoexistAndShowTheirOwnName() = runTest {
        seedList()
        dao.insert(oneOff("i1", "żarówka", createdAt = 100))
        // The unique (listId, productId) index must not merge these: NULLs
        // are distinct, so a second bulb is a second line.
        dao.insert(oneOff("i2", "żarówka", createdAt = 200))

        val rows = dao.observeItems("l1").first()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.productName == "żarówka" && it.productId == null })
        // Position falls back to createdAt, so they keep their add order.
        assertEquals(listOf("i1", "i2"), rows.sortedBy { it.position }.map { it.id })
    }

    @Test
    fun checkoutRemovesCheckedOneOffsReportingThemAndBanksNoStock() = runTest {
        seedList()
        db.productDao().upsert(
            ProductEntity(
                id = "p1",
                name = "Mleko",
                quantity = 1,
                unit = null,
                updatedAt = 1,
                archivedAt = null,
                createdAt = 1,
            ),
        )
        dao.insert(oneOff("i1", "żarówka", createdAt = 100, checkedAt = 150))
        dao.insert(oneOff("i2", "znicz", createdAt = 200)) // unchecked — stays

        val removed = dao.checkoutReportingRemoved("l1", timestamp = 300)

        assertEquals(listOf("i1"), removed)
        assertEquals(listOf("i2"), dao.observeItems("l1").first().map { it.id })
        // No product gained stock from a one-off.
        assertEquals(1, db.productDao().getActive("p1")!!.quantity)
    }

    @Test
    fun aOneOffMovesToAnotherListWithoutAnOrderRow() = runTest {
        seedList()
        dao.upsertList(
            ShoppingListEntity(id = "l2", name = "Targ", createdAt = 1, updatedAt = 1, position = 2.0),
        )
        dao.insert(oneOff("i1", "żarówka", createdAt = 100, checkedAt = 150))

        dao.moveToList("i1", "l2", timestamp = 200)

        val moved = dao.observeItems("l2").first().single()
        assertEquals("i1", moved.id)
        // Arrives unchecked, like any moved item.
        assertEquals(null, moved.checkedAt)
        // And created no product_list_order row (nothing to key it by).
        assertTrue(dao.orderProductIdsOfList("l2").isEmpty())
    }
}
