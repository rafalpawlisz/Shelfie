package io.github.rafalpawlisz.shelfie.data

import java.text.Collator
import java.util.Locale

/**
 * Locale-aware, case-insensitive collator for product names. SQLite's
 * COLLATE NOCASE only folds ASCII, so it misplaces Polish letters (ą, ć, ł,
 * …) after "z"; a [Collator] orders them correctly for the given locale.
 * SECONDARY strength = accents distinguished, case ignored.
 */
internal fun nameCollator(locale: Locale = Locale.getDefault()): Collator =
    Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
