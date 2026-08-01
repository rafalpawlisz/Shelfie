package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.rafalpawlisz.shelfie.emoji.CategorySuggester
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem

@Entity(
    tableName = "shopping_list_items",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ShoppingListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // At most one entry per product within a list (adds merge instead).
        // SQLite treats NULLs as distinct here, so any number of one-off
        // items (productId = NULL) coexist on a list without merging.
        Index(value = ["listId", "productId"], unique = true),
        // Covers the productId FK now that it's no longer the unique index.
        Index(value = ["productId"]),
    ],
)
data class ShoppingListItemEntity(
    @PrimaryKey val id: String,
    val listId: String,
    // null = a one-off item: something bought once without earning a place in
    // the pantry. Such a row carries its own [name] instead, has no stock to
    // bank at checkout, and no manual sort slot (product_list_order is keyed
    // by product).
    val productId: String?,
    // The one-off item's display name; null whenever [productId] is set (the
    // product's name is the name). Exactly one of the two is present.
    val name: String?,
    // How many to buy; > 0 when set. null = "just buy it" — the amount is asked
    // for when the item is checked off, so checkout math still works.
    val amount: Int?,
    // One-off shopping note ("the blue one", "only if on sale") — independent of
    // the product's own notes. Lives and dies with this row: checkout/removal
    // deletes the row, taking the note with it.
    val note: String?,
    // null = still to buy; non-null = in the cart (marked bought). The amount
    // is applied to the product's quantity only at checkout(), not when checked.
    val checkedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

// Flat read model for the list screen: item columns + joined product info.
// For a one-off item (productId = null) productName carries the item's own
// name and the product columns are null.
data class ShoppingListItemRow(
    val id: String,
    val productId: String?,
    val amount: Int?,
    val note: String?,
    val checkedAt: Long?,
    val productName: String,
    val productEmoji: String?,
    val productUnit: String?,
    // From the LEFT JOIN on product_list_order; COALESCE'd to 0.0 when absent.
    val position: Double,
)

fun ShoppingListItemRow.toDomain(): ShoppingListItem = ShoppingListItem(
    id = id,
    productId = productId,
    amount = amount,
    note = note,
    isChecked = checkedAt != null,
    productName = productName,
    productEmoji = sectionEmojiFor(productId, productEmoji, productName),
    productUnit = productUnit,
    position = position,
)

/**
 * The section a shopping-list row shows and sorts by.
 *
 * Where there is a product, the product's own section is the answer and a blank
 * one means "no section", not "guess" — that is the promise the product form
 * makes, and reading a section out of the name here would quietly break it.
 *
 * A one-off has no product to ask, so its name answers instead. Nothing is
 * stored: the line is typed once and deleted at checkout, which is too short a
 * life to be worth a column, a sync field and a picker to correct it.
 */
fun sectionEmojiFor(productId: String?, productEmoji: String?, name: String): String? =
    if (productId != null) productEmoji else CategorySuggester.suggest(name)?.emoji
