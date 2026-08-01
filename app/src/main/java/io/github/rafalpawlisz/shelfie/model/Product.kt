package io.github.rafalpawlisz.shelfie.model

data class Product(
    val id: String,
    val name: String,
    val quantity: Int,
    val unit: String?,
    val minQuantity: Int?,
    val notes: String?,
    val emoji: String?,
    // Best-before date as "yyyy-MM-dd"; null when none was written down.
    val expiresOn: String? = null,
)
