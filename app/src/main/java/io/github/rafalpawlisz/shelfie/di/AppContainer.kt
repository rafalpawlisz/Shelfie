package io.github.rafalpawlisz.shelfie.di

import android.content.Context
import android.util.Log
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
import io.github.rafalpawlisz.shelfie.data.sync.OffsetSyncClock
import io.github.rafalpawlisz.shelfie.data.sync.RoomSyncLocalStore
import io.github.rafalpawlisz.shelfie.data.sync.SharedPreferencesSyncStateStore
import io.github.rafalpawlisz.shelfie.data.sync.SyncApplier
import io.github.rafalpawlisz.shelfie.data.sync.SyncClock
import io.github.rafalpawlisz.shelfie.data.sync.SyncStateStore
import kotlinx.coroutines.CoroutineExceptionHandler
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

    // One scope for app-lifetime background work (the sync engine); never
    // cancelled. The handler matters: a SupervisorJob isolates children from
    // each other but still routes uncaught failures to the default handler,
    // which kills the process. Sync breaking must not take the app down.
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                Log.e("SyncEngine", "sync failed", throwable)
            },
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val syncEngine: DiffSyncEngine by lazy {
        DiffSyncEngine(
            householdIds = authRepository.observeUid()
                .flatMapLatest { uid ->
                    if (uid == null) {
                        flowOf(null)
                    } else {
                        householdRepository.observeHousehold(uid)
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
            syncState = syncStateStore,
            clock = syncClock,
            scope = appScope,
            onSessionStart = { householdId ->
                // The stamp doubles as a clock reference; storing the measured
                // offset is what keeps this device's timestamps comparable with
                // the other one's. ensureSignedIn is a local read here: a
                // session only exists because a signed-in user has a household.
                val uid = authRepository.ensureSignedIn()
                householdRepository.markHouseholdActive(householdId, uid)?.let { offset ->
                    if (offset != syncStateStore.clockOffsetMillis) {
                        Log.i("SyncEngine", "clock offset vs server: ${offset}ms")
                    }
                    syncStateStore.clockOffsetMillis = offset
                }
            },
        )
    }

    val syncStateStore: SyncStateStore by lazy {
        SharedPreferencesSyncStateStore(context)
    }

    /** Shared by the repositories and the engine — see DiffSyncEngine.clock. */
    private val syncClock: SyncClock by lazy { OffsetSyncClock(syncStateStore) }

    val productRepository: ProductRepository by lazy {
        OfflineProductRepository(database.productDao(), syncClock)
    }

    val shoppingListRepository: ShoppingListRepository by lazy {
        OfflineShoppingListRepository(database.shoppingListDao(), syncEngine, syncClock)
    }

    val barcodeRepository: BarcodeRepository by lazy {
        OfflineBarcodeRepository(database.productBarcodeDao(), syncEngine, syncClock)
    }

    val uiPreferences: UiPreferences by lazy {
        SharedPreferencesUiPreferences(context)
    }

    val authRepository: AuthRepository by lazy {
        FirebaseAuthRepository()
    }

    val householdRepository: HouseholdRepository by lazy {
        FirestoreHouseholdRepository(syncState = syncStateStore)
    }
}
