package io.github.rafalpawlisz.shelfie

import android.app.Application
import io.github.rafalpawlisz.shelfie.di.AppContainer

class ShelfieApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Push-sync mirror (Room → Firestore); idles until a household exists.
        // An install with no household never signs in and never reaches the
        // network: the anonymous account is created by the first action that
        // needs a uid, which is creating or joining a household. A session that
        // already exists is restored from disk by Firebase Auth on its own.
        container.syncEngine.start()
    }
}
