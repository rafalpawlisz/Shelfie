package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.data.UiPreferences

class FakeUiPreferences : UiPreferences {
    override var lastRestockListId: String? = null
}
