package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        // Guards two things: the exported 1.json stays in sync with the
        // entities, and the (currently empty) MIGRATIONS array is wired up.
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 1, true, *ShelfieDatabase.MIGRATIONS).close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
