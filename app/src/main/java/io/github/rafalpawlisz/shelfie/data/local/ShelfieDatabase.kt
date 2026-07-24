package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

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
    version = 1,
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
        val MIGRATIONS = emptyArray<androidx.room.migration.Migration>()
    }
}
