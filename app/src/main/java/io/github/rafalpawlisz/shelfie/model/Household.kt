package io.github.rafalpawlisz.shelfie.model

/** The shared-pantry container two (or more) accounts sync through. */
data class Household(
    val id: String,
    val name: String,
    val inviteCode: String,
    val memberIds: Set<String>,
)
