package io.github.rafalpawlisz.shelfie.di

import android.content.Context
import androidx.room.Room
import io.github.rafalpawlisz.shelfie.data.AuthRepository
import io.github.rafalpawlisz.shelfie.data.BarcodeRepository
import io.github.rafalpawlisz.shelfie.data.FirebaseAuthRepository
import io.github.rafalpawlisz.shelfie.data.FirestoreHouseholdRepository
import io.github.rafalpawlisz.shelfie.data.HouseholdRepository
import io.github.rafalpawlisz.shelfie.data.OfflineBarcodeRepository
import io.github.rafalpawlisz.shelfie.data.OfflineProductRepository
import io.github.rafalpawlisz.shelfie.data.OfflineShoppingListRepository
import io.github.rafalpawlisz.shelfie.data.ProductRepository
import io.github.rafalpawlisz.shelfie.data.SharedPreferencesUiPreferences
import io.github.rafalpawlisz.shelfie.data.ShoppingListRepository
import io.github.rafalpawlisz.shelfie.data.UiPreferences
import io.github.rafalpawlisz.shelfie.data.local.ShelfieDatabase
import io.github.rafalpawlisz.shelfie.data.sync.DiffSyncEngine
import io.github.rafalpawlisz.shelfie.data.sync.FirestoreRemoteSource
import io.github.rafalpawlisz.shelfie.data.sync.FirestoreSyncWriter
import io.github.rafalpawlisz.shelfie.data.sync.RoomSyncLocalStore
import io.github.rafalpawlisz.shelfie.data.sync.SyncApplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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

    // One scope for app-lifetime background work (the sync engine); never cancelled.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    val syncEngine: DiffSyncEngine by lazy {
        DiffSyncEngine(
            householdIds = authRepository.observeUser()
                .flatMapLatest { user ->
                    if (user == null) {
                        flowOf(null)
                    } else {
                        householdRepository.observeHousehold(user.uid)
                    }
                }
                .map { it?.id },
            products = database.productDao().observeAllRows(),
            lists = database.shoppingListDao().observeAllListRows(),
            items = database.shoppingListDao().observeAllItemRows(),
            listOrders = database.shoppingListDao().observeAllOrderRows(),
            barcodes = database.productBarcodeDao().observeAll(),
            writer = FirestoreSyncWriter(),
            remote = FirestoreRemoteSource(),
            applier = SyncApplier(
                RoomSyncLocalStore(
                    productDao = database.productDao(),
                    shoppingListDao = database.shoppingListDao(),
                    barcodeDao = database.productBarcodeDao(),
                ),
            ),
            scope = appScope,
        )
    }

    val productRepository: ProductRepository by lazy {
        OfflineProductRepository(database.productDao())
    }

    val shoppingListRepository: ShoppingListRepository by lazy {
        OfflineShoppingListRepository(database.shoppingListDao(), syncEngine)
    }

    val barcodeRepository: BarcodeRepository by lazy {
        OfflineBarcodeRepository(database.productBarcodeDao(), syncEngine)
    }

    val uiPreferences: UiPreferences by lazy {
        SharedPreferencesUiPreferences(context)
    }

    val authRepository: AuthRepository by lazy {
        FirebaseAuthRepository()
    }

    val householdRepository: HouseholdRepository by lazy {
        FirestoreHouseholdRepository()
    }
}
