package io.github.rafalpawlisz.shelfie.model

/**
 * A name bought once before, offered in the picker so it need not be retyped.
 *
 * Not a product: choosing one writes another one-off line, which will leave the
 * list at checkout exactly like the first one did. It carries no amount — that
 * was true of one trip — but keeps the unit, which is a property of the thing.
 */
data class OneOffSuggestion(
    val name: String,
    val unit: String?,
)
