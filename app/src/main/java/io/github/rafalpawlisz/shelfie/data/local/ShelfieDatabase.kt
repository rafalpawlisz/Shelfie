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
    version = 2,
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

        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2)
    }
}
