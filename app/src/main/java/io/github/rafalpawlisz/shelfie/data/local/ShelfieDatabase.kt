package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProductEntity::class,
        ShoppingListEntity::class,
        ShoppingListItemEntity::class,
        ProductBarcodeEntity::class,
        ProductListOrderEntity::class,
    ],
    // Renumbered from 12 back to 1 before the first release — the development
    // history (schema wipes all along) doesn't need to live in the version.
    version = 5,
    exportSchema = true,
)
abstract class ShelfieDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun shoppingListDao(): ShoppingListDao
    abstract fun productBarcodeDao(): ProductBarcodeDao

    companion object {
        /**
         * Every schema step ships an explicit Migration here (with a test in
         * androidTest/MigrationTest against the exported app/schemas JSONs).
         * There is no destructive fallback anymore — forgetting one crashes
         * loudly instead of silently wiping someone's pantry.
         */
        // 1 → 2: sync metadata. product_barcodes gains updatedAt (every other
        // synced table already had it), backfilled from createdAt — barcode
        // rows are immutable, so creation time is the last write.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE product_barcodes " +
                        "ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("UPDATE product_barcodes SET updatedAt = createdAt")
            }
        }

        // 2 → 3: one-off shopping items. shopping_list_items.productId becomes
        // nullable and the row gains its own nullable name — a one-off is a
        // line of text on the list, not a pantry product. SQLite cannot relax
        // NOT NULL in place, so the table is rebuilt; both indices are
        // recreated, and the unique (listId, productId) keeps NULLs distinct,
        // which is what lets several one-offs share a list.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shopping_list_items_new` (" +
                        "`id` TEXT NOT NULL, `listId` TEXT NOT NULL, `productId` TEXT, " +
                        "`name` TEXT, `amount` INTEGER, `note` TEXT, `checkedAt` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`productId`) REFERENCES `products`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`listId`) REFERENCES `shopping_lists`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "INSERT INTO shopping_list_items_new " +
                        "(id, listId, productId, amount, note, checkedAt, createdAt, updatedAt) " +
                        "SELECT id, listId, productId, amount, note, checkedAt, createdAt, updatedAt " +
                        "FROM shopping_list_items"
                )
                db.execSQL("DROP TABLE shopping_list_items")
                db.execSQL("ALTER TABLE shopping_list_items_new RENAME TO shopping_list_items")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_shopping_list_items_listId_productId` " +
                        "ON `shopping_list_items` (`listId`, `productId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_shopping_list_items_productId` " +
                        "ON `shopping_list_items` (`productId`)"
                )
            }
        }

        // 3 → 4: best-before dates. A nullable text column, so the table is
        // added to, not rebuilt, and every existing product simply has no date.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN expiresOn TEXT")
            }
        }

        // 4 → 5: a per-list aisle order. One nullable text column holding the
        // whole order (see SectionOrder), so the table is only added to and
        // every existing list keeps walking the default order.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shopping_lists ADD COLUMN sectionOrder TEXT")
            }
        }

        val MIGRATIONS =
            arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}
