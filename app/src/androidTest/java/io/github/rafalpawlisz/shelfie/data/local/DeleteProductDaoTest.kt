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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deleting an archived product for good, against a real database.
 *
 * Everything worth checking here is SQLite's: the FK cascade that takes the
 * barcodes and order rows with the product, and the fact that an item on an
 * ARCHIVED list is still an item. Fakes cannot show either.
 */
@RunWith(AndroidJUnit4::class)
class DeleteProductDaoTest {

    private lateinit var db: ShelfieDatabase
    private lateinit var dao: ProductDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShelfieDatabase::class.java,
        ).build()
        dao = db.productDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun product(id: String, archived: Boolean) {
        dao.upsert(
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

    private suspend fun list(id: String, archived: Boolean) {
        db.shoppingListDao().upsertList(
            ShoppingListEntity(
                id = id,
                name = id,
                createdAt = 1,
                updatedAt = 1,
                position = 1.0,
                archivedAt = if (archived) 1 else null,
            ),
        )
    }

    private suspend fun item(id: String, listId: String, productId: String) {
        db.shoppingListDao().upsertItem(
            ShoppingListItemEntity(
                id = id,
                listId = listId,
                productId = productId,
                name = null,
                amount = null,
                note = null,
                checkedAt = null,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    @Test
    fun deletingAnArchivedProductTakesItsBarcodesAndOrderRows() = runTest {
        product("p1", archived = true)
        list("l1", archived = false)
        db.productBarcodeDao().insert(ProductBarcodeEntity(barcode = "590001", productId = "p1", createdAt = 1, updatedAt = 1))
        db.productBarcodeDao().insert(ProductBarcodeEntity(barcode = "590002", productId = "p1", createdAt = 1, updatedAt = 1))
        db.shoppingListDao().upsertOrderRow(
            ProductListOrderEntity(listId = "l1", productId = "p1", position = 1.0, updatedAt = 1),
        )

        val removed = dao.deleteArchivedIfUnused("p1")

        assertNotNull("an archived, unreferenced product should be deletable", removed)
        // Reported so the same deletions can be mirrored to the household; the
        // ids would be unreachable after the cascade.
        assertEquals(setOf("590001", "590002"), removed!!.barcodes.toSet())
        assertEquals(listOf("l1"), removed.orderedListIds)
        assertNull(dao.getActive("p1"))
        assertTrue(db.productDao().observeAllRows().first().none { it.id == "p1" })
        assertTrue(db.productBarcodeDao().observeAll().first().isEmpty())
        assertTrue(db.shoppingListDao().observeAllOrderRows().first().isEmpty())
    }

    @Test
    fun anItemOnAnArchivedListStillBlocksDeletion() = runTest {
        product("p1", archived = true)
        list("l1", archived = true)
        item("i1", listId = "l1", productId = "p1")

        assertNull("an archived list can be restored, so its item counts", dao.deleteArchivedIfUnused("p1"))
        assertEquals(1, db.productDao().observeAllRows().first().count { it.id == "p1" })
        // And the item is still there: a refused delete changes nothing.
        assertNotNull(db.shoppingListDao().getById("i1"))
    }

    @Test
    fun anActiveProductIsNeverDeleted() = runTest {
        product("p1", archived = false)

        assertNull("only archived products may be deleted", dao.deleteArchivedIfUnused("p1"))
        assertNotNull(dao.getActive("p1"))
    }
}
