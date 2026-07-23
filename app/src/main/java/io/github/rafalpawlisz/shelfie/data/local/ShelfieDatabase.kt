package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ProductEntity::class], version = 3, exportSchema = true)
abstract class ShelfieDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
}
