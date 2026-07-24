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
    version = 9,
    exportSchema = true,
)
abstract class ShelfieDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun shoppingListDao(): ShoppingListDao
    abstract fun productBarcodeDao(): ProductBarcodeDao
}
