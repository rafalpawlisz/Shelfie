package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.rafalpawlisz.shelfie.model.ItemSlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun aOneOffCarriesItsOwnUnitWhileAProductRowKeepsTheProductsOne() = runTest {
        seedList()
        db.productDao().upsert(
            ProductEntity(
                id = "p1",
                name = "Mleko",
                quantity = 0,
                unit = "l",
                updatedAt = 1,
                archivedAt = null,
                createdAt = 1,
            ),
        )
        dao.addOrMerge("l1", "p1", amount = 2, note = null, newId = "i1", timestamp = 100)
        dao.insert(
            oneOff("i2", "szynka", createdAt = 200).copy(amount = 200, unit = "g"),
        )

        val rows = dao.observeItems("l1").first().associateBy { it.id }
        // The COALESCE, from both sides: the product's unit for a product row,
        // the row's own for a one-off.
        assertEquals("l", rows.getValue("i1").productUnit)
        assertEquals("g", rows.getValue("i2").productUnit)

        // An edit cannot smuggle a unit or a section onto a product row — both
        // live on the product, and the UPDATE guards that rather than trusting
        // callers.
        dao.setDetails(
            "i1",
            amount = 2,
            unit = "beczki",
            note = null,
            sectionEmoji = "🏠",
            timestamp = 300,
        )
        dao.setDetails(
            "i2",
            amount = 3,
            unit = "plastry",
            note = null,
            sectionEmoji = "🏠",
            timestamp = 300,
        )

        val after = dao.observeItems("l1").first().associateBy { it.id }
        assertEquals("l", after.getValue("i1").productUnit)
        assertEquals("plastry", after.getValue("i2").productUnit)
        assertNull(after.getValue("i1").itemSectionEmoji)
        assertEquals("🏠", after.getValue("i2").itemSectionEmoji)
    }

    @Test
    fun aPickedSectionOutlivesTheRowsOwnNameAndTravelsWithTheRow() = runTest {
        seedList()
        dao.insert(oneOff("i1", "kompot", createdAt = 100))
        dao.insert(oneOff("i2", "kompot", createdAt = 200).copy(sectionEmoji = "🍝"))
        // Somebody said "nowhere", which is not the same as having said nothing.
        dao.insert(oneOff("i3", "kompot", createdAt = 300).copy(sectionEmoji = ""))

        val rows = dao.observeItems("l1").first().associateBy { it.id }

        // Only what was actually stored comes back; resolving it is the caller's
        // job, and the three answers have to survive the round trip distinctly.
        assertNull(rows.getValue("i1").itemSectionEmoji)
        assertEquals("🍝", rows.getValue("i2").itemSectionEmoji)
        assertEquals("", rows.getValue("i3").itemSectionEmoji)
    }

    @Test
    fun aOneOffKeepsItsOwnSlotAndAProductRowCannotTakeOneHere() = runTest {
        seedList()
        db.productDao().upsert(
            ProductEntity(
                id = "p1",
                name = "Mleko",
                quantity = 0,
                unit = null,
                updatedAt = 1,
                archivedAt = null,
                createdAt = 1,
            ),
        )
        dao.addOrMerge("l1", "p1", amount = null, note = null, newId = "i1", timestamp = 100)
        dao.insert(oneOff("i2", "znicz", createdAt = 200))

        val before = dao.observeItems("l1").first().associateBy { it.id }
        // The product sorts by its order row; the one-off has no slot yet and
        // falls back to creation time.
        assertEquals(1.0, before.getValue("i1").position, 0.0)
        assertEquals(200.0, before.getValue("i2").position, 0.0)

        dao.setOneOffPosition("i2", position = 0.5, timestamp = 300)
        // The guard: a product row's slot is product_list_order's business, and
        // this statement must not touch it.
        dao.setOneOffPosition("i1", position = 99.0, timestamp = 300)

        val after = dao.observeItems("l1").first().associateBy { it.id }
        assertEquals(0.5, after.getValue("i2").position, 0.0)
        assertEquals(1.0, after.getValue("i1").position, 0.0)
    }

    @Test
    fun setPositionsRenumbersAMixedAisleIntoBothHomes() = runTest {
        seedList()
        db.productDao().upsert(
            ProductEntity(
                id = "p1",
                name = "Mleko",
                quantity = 0,
                unit = null,
                updatedAt = 1,
                archivedAt = null,
                createdAt = 1,
            ),
        )
        dao.addOrMerge("l1", "p1", amount = null, note = null, newId = "i1", timestamp = 100)
        dao.insert(oneOff("i2", "znicz", createdAt = 200))

        // The one-off first, the product second — the point being that one call
        // places both, each in the place its kind of row keeps a slot.
        dao.setPositions(
            listId = "l1",
            slots = listOf(
                ItemSlot(itemId = "i2", productId = null, position = 1.0),
                ItemSlot(itemId = "i1", productId = "p1", position = 2.0),
            ),
            timestamp = 300,
        )

        val rows = dao.observeItems("l1").first().associateBy { it.id }
        assertEquals(1.0, rows.getValue("i2").position, 0.0)
        assertEquals(2.0, rows.getValue("i1").position, 0.0)
        // The product's slot went to its order row, where it outlives the item:
        // removing and re-adding it must find the same place.
        dao.delete("i1")
        dao.addOrMerge("l1", "p1", amount = null, note = null, newId = "i1b", timestamp = 400)
        assertEquals(2.0, dao.observeItems("l1").first().first { it.id == "i1b" }.position, 0.0)
    }

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
