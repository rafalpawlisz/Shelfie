package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.rafalpawlisz.shelfie.data.normalizedOneOffName
import io.github.rafalpawlisz.shelfie.model.OneOffSuggestion
import java.security.MessageDigest

/**
 * A name once bought as a one-off, kept so the picker can offer it again.
 *
 * One-off items are deliberately ephemeral — checkout deletes them, and that is
 * the whole point of the kind. This table is the exception that makes the kind
 * usable: the LINE dies with the shopping trip, the WORD outlives it, so buying
 * grave candles every November does not mean typing "znicze" every November.
 *
 * It holds no amount: how many you wanted was true of one trip. The unit is
 * kept, because "200 g" of something is a property of the thing, not of the
 * trip, and retyping it is the tedious half.
 */
@Entity(tableName = "one_off_suggestions")
data class OneOffSuggestionEntity(
    /**
     * Derived from the name rather than random, so two phones that write the
     * same word land on one row instead of two: the household would otherwise
     * grow a duplicate suggestion every time both of them bought the same thing.
     * A digest rather than the name itself because this doubles as the sync
     * document id, and names contain slashes ("sok 1/2 l") that a path cannot.
     */
    @PrimaryKey val id: String,
    /** As typed, for display; [id] carries the case- and space-insensitive identity. */
    val name: String,
    /** The unit last used with this name, pre-filled next time; null = a bare count. */
    val unit: String?,
    /** Newest first in the picker — the thing bought last month beats last year. */
    val lastUsedAt: Long,
    val updatedAt: Long,
)

fun OneOffSuggestionEntity.toDomain(): OneOffSuggestion = OneOffSuggestion(
    name = name,
    unit = unit,
)

/**
 * The id of a one-off name: the canonical [normalizedOneOffName] form,
 * digested. "Znicze", "znicze " and "znicze" are one entry; anything more
 * clever (stemming, diacritics) would merge words a person means to keep
 * apart.
 */
fun oneOffSuggestionId(name: String): String {
    val normalized = normalizedOneOffName(name)
    val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
