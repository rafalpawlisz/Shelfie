package io.github.rafalpawlisz.shelfie.di

import android.content.Context
import androidx.room.Room
import io.github.rafalpawlisz.shelfie.data.OfflineProductRepository
import io.github.rafalpawlisz.shelfie.data.ProductRepository
import io.github.rafalpawlisz.shelfie.data.local.ShelfieDatabase

class AppContainer(context: Context) {

    private val database: ShelfieDatabase = Room.databaseBuilder(
        context = context.applicationContext,
        klass = ShelfieDatabase::class.java,
        name = "shelfie.db",
    )
        // Pre-release: wipe on schema change instead of writing migrations.
        // Replace with real Migration objects once user data matters.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    val productRepository: ProductRepository by lazy {
        OfflineProductRepository(database.productDao())
    }
}
