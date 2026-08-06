package io.github.rafalpawlisz.shelfie.data

import android.content.Context
import androidx.core.content.edit

/**
 * Small, local UI preferences (not synced, not part of the pantry data).
 * Interface so tests can use an in-memory fake.
 */
interface UiPreferences {
    /** The list last chosen in the restock dialog; preselected next time. */
    var lastRestockListId: String?
}

class SharedPreferencesUiPreferences(context: Context) : UiPreferences {

    private val prefs =
        context.applicationContext.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    override var lastRestockListId: String?
        get() = prefs.getString(KEY_LAST_RESTOCK_LIST, null)
        set(value) {
            prefs.edit { putString(KEY_LAST_RESTOCK_LIST, value) }
        }

    private companion object {
        const val KEY_LAST_RESTOCK_LIST = "lastRestockListId"
    }
}
