package io.github.rafalpawlisz.shelfie.di

import android.content.Context
import androidx.room.Room
import io.github.rafalpawlisz.shelfie.data.BarcodeRepository
import io.github.rafalpawlisz.shelfie.data.OfflineBarcodeRepository
import io.github.rafalpawlisz.shelfie.data.OfflineProductRepository
import io.github.rafalpawlisz.shelfie.data.OfflineShoppingListRepository
import io.github.rafalpawlisz.shelfie.data.ProductRepository
import io.github.rafalpawlisz.shelfie.data.ShoppingListRepository
import io.github.rafalpawlisz.shelfie.data.local.ShelfieDatabase

class AppContainer(context: Context) {

    private val database: ShelfieDatabase = Room.databaseBuilder(
        context = context.applicationContext,
        klass = ShelfieDatabase::class.java,
        name = "shelfie.db",
    )
        // Schema-experimentation phase: wipe on any version change instead of
        // writing migrations. Switch to explicit Migration objects before real
        // pantry data goes in (second user / sync work).
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    val productRepository: ProductRepository by lazy {
        OfflineProductRepository(database.productDao())
    }

    val shoppingListRepository: ShoppingListRepository by lazy {
        OfflineShoppingListRepository(database.shoppingListDao())
    }

    val barcodeRepository: BarcodeRepository by lazy {
        OfflineBarcodeRepository(database.productBarcodeDao())
    }
}
