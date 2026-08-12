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

    @Test
    fun migrate2To3_rebuildsItemsKeepingRowsAndConstraints() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO products " +
                    "(id, name, quantity, unit, updatedAt, archivedAt, createdAt, " +
                    "minQuantity, notes, emoji) " +
                    "VALUES ('p1', 'Milk', 2, 'l', 111, NULL, 100, 4, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO shopping_lists (id, name, createdAt, updatedAt, position, archivedAt) " +
                    "VALUES ('l1', 'Sklep', 100, 100, 1.0, NULL)"
            )
            db.execSQL(
                "INSERT INTO shopping_list_items " +
                    "(id, listId, productId, amount, note, checkedAt, createdAt, updatedAt) " +
                    "VALUES ('i1', 'l1', 'p1', 2, 'the blue one', NULL, 100, 100)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, *ShelfieDatabase.MIGRATIONS).use { db ->
            // The existing row survived the rebuild, name arriving as NULL.
            db.query(
                "SELECT productId, name, amount, note FROM shopping_list_items WHERE id = 'i1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("p1", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals(2, cursor.getInt(2))
                assertEquals("the blue one", cursor.getString(3))
            }
            // What the rebuild exists for: a one-off row with no product — and
            // two of them, because the unique index keeps NULLs distinct.
            db.execSQL(
                "INSERT INTO shopping_list_items " +
                    "(id, listId, productId, name, amount, note, checkedAt, createdAt, updatedAt) " +
                    "VALUES ('i2', 'l1', NULL, 'żarówka', NULL, NULL, NULL, 200, 200)"
            )
            db.execSQL(
                "INSERT INTO shopping_list_items " +
                    "(id, listId, productId, name, amount, note, checkedAt, createdAt, updatedAt) " +
                    "VALUES ('i3', 'l1', NULL, 'żarówka', NULL, NULL, NULL, 300, 300)"
            )
            // The FK cascade still works after the rebuild: dropping the
            // product takes its item along and leaves the one-offs alone.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM products WHERE id = 'p1'")
            db.query("SELECT id FROM shopping_list_items ORDER BY id").use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("i2", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrate3To4_addsExpiryLeavingProductsIntact() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO products " +
                    "(id, name, quantity, unit, updatedAt, archivedAt, createdAt, " +
                    "minQuantity, notes, emoji) " +
                    "VALUES ('p1', 'Syrop', 1, NULL, 111, NULL, 100, NULL, 'z apteki', '💊')"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 4, true, *ShelfieDatabase.MIGRATIONS).use { db ->
            db.query("SELECT name, notes, emoji, expiresOn FROM products").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Syrop", cursor.getString(0))
                assertEquals("z apteki", cursor.getString(1))
                assertEquals("💊", cursor.getString(2))
                // Nobody wrote a date for what was already in the pantry.
                assertTrue(cursor.isNull(3))
            }
            db.execSQL("UPDATE products SET expiresOn = '2026-09-30' WHERE id = 'p1'")
            db.query("SELECT expiresOn FROM products WHERE id = 'p1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("2026-09-30", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrate4To5_addsAisleOrderLeavingListsIntact() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO shopping_lists (id, name, createdAt, updatedAt, position, archivedAt) " +
                    "VALUES ('l1', 'Lidl', 100, 100, 1.0, NULL)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, *ShelfieDatabase.MIGRATIONS).use { db ->
            db.query("SELECT name, position, sectionOrder FROM shopping_lists").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Lidl", cursor.getString(0))
                assertEquals(1.0, cursor.getDouble(1), 0.0)
                // An existing list walks the default order until someone says
                // otherwise, and "nobody said" is expressed by NULL.
                assertTrue(cursor.isNull(2))
            }
            db.execSQL("UPDATE shopping_lists SET sectionOrder = 'HYGIENE,BREAD' WHERE id = 'l1'")
            db.query("SELECT sectionOrder FROM shopping_lists WHERE id = 'l1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("HYGIENE,BREAD", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrate5To6_addsUnitLeavingOneOffItemsIntact() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO shopping_lists (id, name, createdAt, updatedAt, position, archivedAt, " +
                    "sectionOrder) VALUES ('l1', 'Lidl', 100, 100, 1.0, NULL, NULL)"
            )
            // A one-off written down before units existed: "3" and nothing more.
            db.execSQL(
                "INSERT INTO shopping_list_items (id, listId, productId, name, amount, note, " +
                    "checkedAt, createdAt, updatedAt) " +
                    "VALUES ('i1', 'l1', NULL, 'znicz', 3, NULL, NULL, 100, 100)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 6, true, *ShelfieDatabase.MIGRATIONS).use { db ->
            db.query("SELECT name, amount, unit FROM shopping_list_items WHERE id = 'i1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("znicz", c.getString(0))
                assertEquals(3, c.getInt(1))
                // Still a bare count, which is what it always was.
                assertTrue(c.isNull(2))
            }
            db.execSQL("UPDATE shopping_list_items SET unit = 'opakowania' WHERE id = 'i1'")
            db.query("SELECT unit FROM shopping_list_items WHERE id = 'i1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("opakowania", c.getString(0))
            }
        }
    }

    @Test
    fun migrate6To7_addsAOneOffSlotWithoutDisturbingCreationOrder() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO shopping_lists (id, name, createdAt, updatedAt, position, archivedAt, " +
                    "sectionOrder) VALUES ('l1', 'Lidl', 100, 100, 1.0, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO shopping_list_items (id, listId, productId, name, amount, unit, note, " +
                    "checkedAt, createdAt, updatedAt) " +
                    "VALUES ('i1', 'l1', NULL, 'znicz', NULL, NULL, NULL, NULL, 100, 100)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 7, true, *ShelfieDatabase.MIGRATIONS).use { db ->
            db.query("SELECT name, position FROM shopping_list_items WHERE id = 'i1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("znicz", c.getString(0))
                // Unplaced, so it keeps sorting by creation time as before.
                assertTrue(c.isNull(1))
            }
            db.execSQL("UPDATE shopping_list_items SET position = 2.5 WHERE id = 'i1'")
            db.query("SELECT position FROM shopping_list_items WHERE id = 'i1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(2.5, c.getDouble(0), 0.0)
            }
        }
    }

    @Test
    fun migrate7To8_addsTheOneOffVocabularyEmpty() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO shopping_lists (id, name, createdAt, updatedAt, position, archivedAt, " +
                    "sectionOrder) VALUES ('l1', 'Lidl', 100, 100, 1.0, NULL, NULL)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 8, true, *ShelfieDatabase.MIGRATIONS).use { db ->
            // Nothing to carry over: the history starts empty and fills from the
            // next one-off onwards. What matters is that the table is there and
            // the list beside it came through untouched.
            db.query("SELECT COUNT(*) FROM one_off_suggestions").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            db.execSQL(
                "INSERT INTO one_off_suggestions (id, name, unit, lastUsedAt, updatedAt) " +
                    "VALUES ('abc', 'znicze', 'sztuki', 100, 100)"
            )
            db.query("SELECT name, unit FROM one_off_suggestions WHERE id = 'abc'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("znicze", c.getString(0))
                assertEquals("sztuki", c.getString(1))
            }
            db.query("SELECT name FROM shopping_lists WHERE id = 'l1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Lidl", c.getString(0))
            }
        }
    }

    @Test
    fun migrate8To9_leavesExistingLinesReadingTheirSectionFromTheirName() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO shopping_lists (id, name, createdAt, updatedAt, position, archivedAt, " +
                    "sectionOrder) VALUES ('l1', 'Lidl', 100, 100, 1.0, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO shopping_list_items (id, listId, productId, name, amount, unit, note, " +
                    "position, checkedAt, createdAt, updatedAt) " +
                    "VALUES ('i1', 'l1', NULL, 'znicz', NULL, NULL, NULL, NULL, NULL, 100, 100)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 9, true, *ShelfieDatabase.MIGRATIONS).use { db ->
            db.query("SELECT name, sectionEmoji FROM shopping_list_items WHERE id = 'i1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("znicz", c.getString(0))
                // Null, which is the only honest answer for a line written
                // before there was anything to pick: it goes on reading its
                // section out of its name, exactly as it did yesterday.
                assertTrue(c.isNull(1))
            }
            // And the column can hold both of the things a pick can mean.
            db.execSQL("UPDATE shopping_list_items SET sectionEmoji = '🏠' WHERE id = 'i1'")
            db.query("SELECT sectionEmoji FROM shopping_list_items WHERE id = 'i1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("🏠", c.getString(0))
            }
            db.execSQL("UPDATE shopping_list_items SET sectionEmoji = '' WHERE id = 'i1'")
            db.query("SELECT sectionEmoji FROM shopping_list_items WHERE id = 'i1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("", c.getString(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val CURRENT_VERSION = 8
    }
}
