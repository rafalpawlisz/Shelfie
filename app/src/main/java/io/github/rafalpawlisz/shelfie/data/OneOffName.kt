package io.github.rafalpawlisz.shelfie.data

import java.util.Locale

private val ONE_OFF_WHITESPACE = Regex("\\s+")

/**
 * The canonical identity of a one-off name: trimmed, lowercased, inner
 * whitespace collapsed. "Znicze", "znicze " and "znicze  200 g" are the same
 * word the suggestion table keys them by — oneOffSuggestionId digests exactly
 * this form, so an on-list lookup here and the remembered-name table can
 * never disagree about what a word is.
 */
internal fun normalizedOneOffName(name: String): String =
    name.trim().lowercase(Locale.ROOT).replace(ONE_OFF_WHITESPACE, " ")
