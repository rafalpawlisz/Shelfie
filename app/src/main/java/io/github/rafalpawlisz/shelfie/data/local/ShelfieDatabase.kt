package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProductEntity::class, ShoppingListItemEntity::class, ProductBarcodeEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class ShelfieDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun shoppingListDao(): ShoppingListDao
    abstract fun productBarcodeDao(): ProductBarcodeDao
}
