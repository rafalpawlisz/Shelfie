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
 * Why planning a product has to bring it back from the archive.
 *
 * observeItems joins products with `archivedAt IS NULL`, so an item pointing at
 * an archived product is not hidden by any Kotlin decision — it simply is not
 * in the query's result. This test states that fact, because a whole path in
 * PantryViewModel exists to avoid creating such a row.
 */
@RunWith(AndroidJUnit4::class)
class ArchivedItemVisibilityDaoTest {

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

    @Test
    fun anItemOfAnArchivedProductIsInvisibleUntilTheProductComesBack() = runTest {
        db.productDao().upsert(
            ProductEntity(
                id = "p1",
                name = "Groszek",
                quantity = 0,
                unit = "puszka",
                updatedAt = 1,
                archivedAt = 1,
                createdAt = 1,
            ),
        )
        dao.upsertList(
            ShoppingListEntity(id = "l1", name = "Sklep", createdAt = 1, updatedAt = 1, position = 1.0),
        )
        dao.upsertItem(
            ShoppingListItemEntity(
                id = "i1",
                listId = "l1",
                productId = "p1",
                amount = 2,
                note = null,
                checkedAt = null,
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        // The row exists and still cannot be seen: this is the ghost item that
        // planning an archived product would produce.
        assertTrue("an archived product's item must not show", dao.observeItems("l1").first().isEmpty())

        db.productDao().restore("p1", timestamp = 2)

        val visible = dao.observeItems("l1").first()
        assertEquals(1, visible.size)
        assertEquals("p1", visible.single().productId)
        assertEquals(2, visible.single().amount)
    }
}
