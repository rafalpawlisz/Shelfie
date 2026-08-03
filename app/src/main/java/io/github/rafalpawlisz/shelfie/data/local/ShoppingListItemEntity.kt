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
    // What the [amount] counts: "g", "opakowania". Only ever set on a one-off —
    // where there is a product, the product's unit is the unit, the same way its
    // name is the name. null = a bare count.
    val unit: String? = null,
    // One-off shopping note ("the blue one", "only if on sale") — independent of
    // the product's own notes. Lives and dies with this row: checkout/removal
    // deletes the row, taking the note with it.
    val note: String?,
    // A one-off's manual sort slot within its section, once it has been dragged;
    // null until then, and the row sorts by creation time instead. Only ever set
    // where [productId] is null: a product's slot lives in product_list_order,
    // because it has to outlive the row (removing and re-adding a product must
    // return it to its place). A one-off has nothing to outlive — it dies at
    // checkout — so its slot belongs on the row.
    val position: Double? = null,
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
    // What the row sorts by: the product's slot, or a one-off's own slot, or —
    // for a one-off nobody has dragged — its creation time.
    val position: Double,
    // The slot as an actual assignment rather than a fallback: null means this
    // row has never been placed by hand (an undragged one-off), and so must not
    // be borrowed as a position neighbour — its "position" is a timestamp in
    // millis, which would push a real neighbour to the end of the aisle for good.
    val manualPosition: Double?,
    // The owning list's aisle order, straight from shopping_lists; null = the
    // default order.
    val sectionOrder: String? = null,
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
    hasManualPosition = manualPosition != null,
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
