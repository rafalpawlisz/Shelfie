package io.github.rafalpawlisz.shelfie.ui.pantry

import io.github.rafalpawlisz.shelfie.model.Product
import java.time.LocalDate

/** How a best-before date reads today: already past, or close enough to act on. */
enum class ExpiryStatus { EXPIRED, SOON }

/**
 * The status of a stored "yyyy-MM-dd" date, or null when there is nothing to
 * say — no date, an unreadable one, or one comfortably far away.
 *
 * [within] is deliberately generous. The products this exists for are the
 * rarely touched ones at the back of the cupboard, and being told on the last
 * day is being told too late: a jar of syrup needs weeks to be used up, not
 * hours. The date itself still counts as good — "best before" means through
 * that day.
 */
fun expiryStatusOf(
    expiresOn: String?,
    today: LocalDate,
    within: Long = DEFAULT_WARNING_DAYS,
): ExpiryStatus? {
    val date = expiresOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    return when {
        date.isBefore(today) -> ExpiryStatus.EXPIRED
        !date.isAfter(today.plusDays(within)) -> ExpiryStatus.SOON
        else -> null
    }
}

/** The soonest-first block: expired ones lead, then whatever runs out next. */
internal fun List<Product>.expiringFirst(today: LocalDate): List<Product> =
    filter { expiryStatusOf(it.expiresOn, today) != null }
        // The dates are ISO text, so plain string order is chronological.
        .sortedBy { it.expiresOn }

const val DEFAULT_WARNING_DAYS = 30L
