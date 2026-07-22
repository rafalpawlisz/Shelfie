package io.github.rafalpawlisz.shelfie.model

data class Product(
    val id: String,
    val name: String,
    val quantity: Int,
    val unit: String?,
)
