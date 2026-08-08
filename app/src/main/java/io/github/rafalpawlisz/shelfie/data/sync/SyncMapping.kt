package io.github.rafalpawlisz.shelfie.data.sync

import io.github.rafalpawlisz.shelfie.data.local.OneOffSuggestionEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductBarcodeEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductEntity
import io.github.rafalpawlisz.shelfie.data.local.ProductListOrderEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListEntity
import io.github.rafalpawlisz.shelfie.data.local.ShoppingListItemEntity

// Row → document payload. Full-state set() documents: idempotent, and the
// updatedAt field is what last-write-wins conflict resolution keys on.

fun ProductEntity.toSyncDoc(): Map<String, Any?> = mapOf(
    "name" to name,
    "quantity" to quantity,
    "unit" to unit,
    "minQuantity" to minQuantity,
    "notes" to notes,
    "emoji" to emoji,
    "expiresOn" to expiresOn,
    "archivedAt" to archivedAt,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
)

fun ShoppingListEntity.toSyncDoc(): Map<String, Any?> = mapOf(
    "name" to name,
    "position" to position,
    "sectionOrder" to sectionOrder,
    "archivedAt" to archivedAt,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
)

fun OneOffSuggestionEntity.toSyncDoc(): Map<String, Any?> = mapOf(
    // The id is derived from the name, so both phones write the same document
    // for the same word and the household keeps one entry, not two.
    "name" to name,
    "unit" to unit,
    "lastUsedAt" to lastUsedAt,
    "updatedAt" to updatedAt,
)

fun ShoppingListItemEntity.toSyncDoc(): Map<String, Any?> = mapOf(
    "listId" to listId,
    "productId" to productId,
    // The one-off item's own display name; null for product-backed items.
    // Older app versions cannot store a null productId, so a one-off document
    // fails to apply there — both phones must run a version that knows the
    // field before one-offs land on a shared list.
    "name" to name,
    "amount" to amount,
    // What the amount counts on a one-off; an older app just ignores the field
    // and shows the bare number, which is what it did before this existed.
    "unit" to unit,
    // A one-off's own slot within its section; absent until it is dragged, and
    // an older app just keeps sorting it by creation time.
    "position" to position,
    "note" to note,
    "checkedAt" to checkedAt,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
)

fun ProductBarcodeEntity.toSyncDoc(): Map<String, Any?> = mapOf(
    "productId" to productId,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
)

fun ProductListOrderEntity.toSyncDoc(): Map<String, Any?> = mapOf(
    "listId" to listId,
    "productId" to productId,
    "position" to position,
    "updatedAt" to updatedAt,
)
