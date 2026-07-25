package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration harness against the exported schemas in app/schemas (the Room
 * Gradle plugin ships them as androidTest assets).
 *
 * The pattern for every future schema step N → N+1:
 *  1. create the database at version N ([MigrationTestHelper.createDatabase]),
 *  2. insert representative rows with raw SQL (entity classes describe N+1,
 *     so they must not be used here),
 *  3. run [MigrationTestHelper.runMigrationsAndValidate] up to N+1,
 *  4. assert the rows survived.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ShelfieDatabase::class.java,
    )

    @Test
    fun exportedSchemaOpensAndValidatesAtCurrentVersion() {
        // Guards the exported current-version JSON staying in sync with the
        // compiled entities.
        helper.createDatabase(TEST_DB, CURRENT_VERSION).close()
        helper.runMigrationsAndValidate(TEST_DB, CURRENT_VERSION, true).close()
    }

    @Test
    fun migrate1To2_backfillsBarcodeUpdatedAtFromCreatedAt() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO products " +
                    "(id, name, quantity, unit, updatedAt, archivedAt, createdAt, " +
                    "minQuantity, notes, emoji) " +
                    "VALUES ('p1', 'Milk', 2, 'l', 111, NULL, 100, 4, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO product_barcodes (barcode, productId, createdAt) " +
                    "VALUES ('5901234123457', 'p1', 222)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, *ShelfieDatabase.MIGRATIONS).use { db ->
            db.query("SELECT createdAt, updatedAt FROM product_barcodes").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(222L, cursor.getLong(0))
                assertEquals(222L, cursor.getLong(1)) // backfilled from createdAt
                assertEquals(1, cursor.count) // the row survived, nothing else appeared
            }
            // The product row rode along untouched.
            db.query("SELECT name, quantity FROM products").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Milk", cursor.getString(0))
                assertEquals(2, cursor.getInt(1))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val CURRENT_VERSION = 2
    }
}
