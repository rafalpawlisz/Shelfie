package io.github.rafalpawlisz.shelfie.emoji

/**
 * Looks a product name up in a hand-written dictionary of Polish words.
 *
 * Two dictionaries use this: one answers with a store section, the other with a
 * decorative emoji. The matching rules are the interesting part and they are
 * the same for both, so they live here once — a fix like "the longer phrase
 * wins" applied to a copy would be a fix in one dictionary only.
 *
 * Polish inflection is the real work. "Jabłko", "jabłka" and "jabłkach" have to
 * reach the same entry, so both sides are reduced by the same crude stemmer;
 * irregular forms no suffix rule can reach ("jajek" from "jajko") are simply
 * listed in the dictionary.
 */
internal class WordDictionary<T : Any>(entries: List<Pair<T, List<String>>>) {

    /**
     * Multi-word keys, longest first, matched as substrings of the name.
     *
     * Length decides, not dictionary order: a phrase that contains another
     * phrase is the more specific of the two, and matching by dictionary order
     * made the longer one unreachable — "paluszki rybne mrozone" could never
     * beat "paluszki rybne".
     */
    private val phrases: List<Pair<String, T>> = entries
        .flatMap { (value, words) -> words.filter { ' ' in it }.map { it to value } }
        .sortedByDescending { (phrase, _) -> phrase.length }

    /** Single words, reduced to stems; the first entry to claim a stem keeps it. */
    private val stems: Map<String, T> = buildMap {
        for ((value, words) in entries) {
            for (word in words) {
                if (' ' in word) continue
                putIfAbsent(stem(word), value)
            }
        }
    }

    fun lookup(productName: String): T? {
        val normalized = normalize(productName)
        if (normalized.isBlank()) return null

        // Multi-word entries first ("ogórki kiszone"): a phrase carries more
        // meaning than either of its words alone — pickled things live in the
        // jar aisle, not among the vegetables.
        phrases.firstOrNull { (phrase, _) -> normalized.contains(phrase) }
            ?.let { return it.second }

        // Then word by word, left to right. Polish puts the head noun first —
        // "mleko owsiane", "ser żółty", "masło orzechowe" — so the first word
        // that means anything is the one the answer should follow.
        for (word in normalized.split(' ')) {
            stems[stem(word)]?.let { return it }
        }
        return null
    }
}

/** Lowercase, strip diacritics, drop anything that is not a letter or digit. */
private fun normalize(raw: String): String = buildString {
    for (char in raw.lowercase()) {
        val mapped = DIACRITICS[char] ?: char
        when {
            mapped.isLetterOrDigit() -> append(mapped)
            // Any separator collapses to a single space.
            isNotEmpty() && last() != ' ' -> append(' ')
        }
    }
}.trim()

/**
 * Strips one Polish inflection ending, and only when enough of the word is left
 * to still mean something. Applied to the dictionary too, so both sides land on
 * the same shape; being wrong is harmless as long as it is wrong identically on
 * both sides.
 */
private fun stem(word: String): String {
    if (word.length < 4) return word
    for (suffix in SUFFIXES) {
        if (word.endsWith(suffix) && word.length - suffix.length >= 3) {
            return word.dropLast(suffix.length)
        }
    }
    return word
}

// Longest first: "iami" must win over "ami" over "i".
private val SUFFIXES = listOf(
    "iami", "ami", "ach", "owi", "ow", "om", "ie", "em", "y", "i", "a", "e", "u", "o",
)

private val DIACRITICS = mapOf(
    'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
    'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
)
