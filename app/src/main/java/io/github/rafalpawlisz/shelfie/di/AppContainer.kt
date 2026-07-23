package io.github.rafalpawlisz.shelfie.di

import android.content.Context
import androidx.room.Room
import io.github.rafalpawlisz.shelfie.data.OfflineProductRepository
import io.github.rafalpawlisz.shelfie.data.ProductRepository
import io.github.rafalpawlisz.shelfie.data.local.MIGRATION_1_2
import io.github.rafalpawlisz.shelfie.data.local.MIGRATION_2_3
import io.github.rafalpawlisz.shelfie.data.local.ShelfieDatabase

class AppContainer(context: Context) {

    private val database: ShelfieDatabase = Room.databaseBuilder(
        context = context.applicationContext,
        klass = ShelfieDatabase::class.java,
        name = "shelfie.db",
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        // Safety valve for installing an older build over a newer schema;
        // upgrades always go through explicit migrations above.
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()

    val productRepository: ProductRepository by lazy {
        OfflineProductRepository(database.productDao())
    }
}
