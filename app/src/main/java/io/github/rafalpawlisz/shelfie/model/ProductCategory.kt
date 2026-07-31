package io.github.rafalpawlisz.shelfie.model

import androidx.annotation.StringRes
import io.github.rafalpawlisz.shelfie.R

/**
 * The closed list of store sections a product can belong to. The section's
 * emoji is what gets stored in the product's `emoji` field and what the other
 * phone sees — emoji are unique per section, so the emoji IS the key, which is
 * why this needed no schema or sync change.
 *
 * Declaration order is the picker's display order, laid out roughly the way a
 * store is walked.
 */
enum class ProductCategory(val emoji: String, @param:StringRes val nameRes: Int) {
    PRODUCE("🍎", R.string.category_produce),
    BREAD("🍞", R.string.category_bread),
    DAIRY("🥛", R.string.category_dairy),
    MEAT("🥩", R.string.category_meat),
    FISH("🐟", R.string.category_fish),
    FROZEN("🧊", R.string.category_frozen),
    CANNED("🥫", R.string.category_canned),
    DRY_GOODS("🍝", R.string.category_dry_goods),
    SPICES("🧂", R.string.category_spices),
    SWEETS("🍫", R.string.category_sweets),
    DRINKS("🧃", R.string.category_drinks),
    ALCOHOL("🍷", R.string.category_alcohol),
    CLEANING("🧴", R.string.category_cleaning),
    HYGIENE("🧼", R.string.category_hygiene),
    PHARMACY("💊", R.string.category_pharmacy),
    HOME("🏠", R.string.category_home),
    ;

    companion object {
        /**
         * The section a stored emoji stands for, or null for blank and for
         * emoji from before sections existed. Such rows keep showing whatever
         * they hold and group with the sectionless ones until somebody picks a
         * section for them: nothing assigns one behind the user's back, not
         * even a save (see ProductFormDialog.suggestSection). A pantry filled
         * before sections existed therefore stays one sectionless block until
         * its products are visited.
         */
        fun fromEmoji(emoji: String?): ProductCategory? =
            entries.firstOrNull { it.emoji == emoji?.trim() }
    }
}
