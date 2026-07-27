package io.github.rafalpawlisz.shelfie

import android.app.Application
import io.github.rafalpawlisz.shelfie.di.AppContainer

class ShelfieApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Push-sync mirror (Room → Firestore); idles until a household exists.
        container.syncEngine.start()
        // Anonymous identity for this install. Deliberately only on a cold
        // start: after an explicit sign-out the user is choosing to be without
        // one, and re-creating it here would send the next Google sign-in down
        // the account-collision path for no reason.
        container.bootstrapSession()
    }
}
