package io.github.rafalpawlisz.shelfie.di

import android.content.Context
import androidx.room.Room
import io.github.rafalpawlisz.shelfie.data.BarcodeRepository
import io.github.rafalpawlisz.shelfie.data.OfflineBarcodeRepository
import io.github.rafalpawlisz.shelfie.data.OfflineProductRepository
import io.github.rafalpawlisz.shelfie.data.OfflineShoppingListRepository
import io.github.rafalpawlisz.shelfie.data.ProductRepository
import io.github.rafalpawlisz.shelfie.data.SharedPreferencesUiPreferences
import io.github.rafalpawlisz.shelfie.data.ShoppingListRepository
import io.github.rafalpawlisz.shelfie.data.UiPreferences
import io.github.rafalpawlisz.shelfie.data.local.ShelfieDatabase

class AppContainer(private val context: Context) {

    private val database: ShelfieDatabase = Room.databaseBuilder(
        context = context.applicationContext,
        klass = ShelfieDatabase::class.java,
        name = "shelfie.db",
    )
        // No destructive fallback: real pantry data lives here now, so every
        // schema change must ship an explicit Migration (see MIGRATIONS) with
        // a test against the exported schemas in app/schemas.
        .addMigrations(*ShelfieDatabase.MIGRATIONS)
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

    val uiPreferences: UiPreferences by lazy {
        SharedPreferencesUiPreferences(context)
    }
}
