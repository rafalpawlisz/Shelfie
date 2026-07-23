package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test"

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ShelfieDatabase::class.java,
    )

    @Test
    fun migrate1To2_addsNullArchivedAt() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO products (id, name, quantity, unit, updatedAt) " +
                    "VALUES ('a', 'Milk', 2, 'l', 111)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.query("SELECT archivedAt FROM products WHERE id = 'a'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
    }

    @Test
    fun migrate2To3_backfillsCreatedAtFromUpdatedAt() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO products (id, name, quantity, unit, updatedAt, archivedAt) " +
                    "VALUES ('a', 'Milk', 2, 'l', 111, NULL)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        db.query("SELECT createdAt, minQuantity, notes FROM products WHERE id = 'a'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(111L, cursor.getLong(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
    }

    @Test
    fun migrateAll_from1To3() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO products (id, name, quantity, unit, updatedAt) " +
                    "VALUES ('a', 'Milk', 2, 'l', 111)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_1_2, MIGRATION_2_3)

        db.query(
            "SELECT name, quantity, archivedAt, createdAt FROM products WHERE id = 'a'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Milk", cursor.getString(0))
            assertEquals(2, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
            assertEquals(111L, cursor.getLong(3))
        }
    }
}
