package io.github.rafalpawlisz.shelfie

import android.app.Application
import io.github.rafalpawlisz.shelfie.di.AppContainer

class ShelfieApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
