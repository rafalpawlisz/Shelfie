package io.github.rafalpawlisz.shelfie

import android.app.Application
import io.github.rafalpawlisz.shelfie.di.AppContainer

class ShelfieApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Push-sync mirror (Room → Firestore); idles until a household exists.
        container.syncEngine.start()
    }
}
