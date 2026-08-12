package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.ProductCategory

/**
 * The store-section picker, shared by the product form and the shopping-list
 * dialogs: a read-only field opening the closed list of sections. A legacy emoji
 * (from before sections existed) shows as itself with no name; picking anything
 * replaces it for good.
 *
 * It lives in its own file because two dialogs now ask the same question, and a
 * copy of it in each is a copy that will answer differently one day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SectionPickerField(
    // Only to tell "nothing typed yet" from "typed, and unrecognised" — the
    // suggestion is null in both cases.
    name: String,
    selectedEmoji: String,
    suggestion: ProductCategory?,
    onPick: (ProductCategory?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = ProductCategory.fromEmoji(selectedEmoji)
    val label = when {
        selected != null -> "${selected.emoji}  ${stringResource(selected.nameRes)}"
        selectedEmoji.isNotBlank() -> selectedEmoji
        else -> stringResource(R.string.category_none)
    }
    val note = sectionNoteFor(name, selectedEmoji, suggestion)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.product_category_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.category_none)) },
                onClick = {
                    onPick(null)
                    expanded = false
                },
            )
            ProductCategory.entries.forEach { category ->
                DropdownMenuItem(
                    text = { Text("${category.emoji}  ${stringResource(category.nameRes)}") },
                    onClick = {
                        onPick(category)
                        expanded = false
                    },
                )
            }
        }
    }
    if (note != null) {
        Text(
            text = when (note) {
                is SectionNote.FromName -> stringResource(
                    R.string.category_from_name,
                    "${note.category.emoji}  ${stringResource(note.category.nameRes)}",
                )
                SectionNote.NameUnknown -> stringResource(R.string.category_name_unknown)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/**
 * What the closed section field displays: the pick once one is made, else
 * whatever the row already stores ("" is an answered "no section" and shows as
 * one), else what the dictionary implies. The add dialog passes stored = null —
 * a line being created stores nothing yet.
 */
internal fun shownSectionEmoji(
    touched: Boolean,
    picked: String,
    stored: String?,
    suggested: ProductCategory?,
): String = when {
    touched -> picked
    stored != null -> stored
    else -> suggested?.emoji.orEmpty()
}

/**
 * What a save writes back: the pick, or — untouched — exactly what was stored
 * before, null included. Never [shownSectionEmoji]'s answer: the field may show
 * the dictionary's suggestion, but writing that down because an edit happened
 * to pass through would freeze the day's guess into an answer, and the line
 * would stop following the dictionary as it improves. The two being separate
 * functions, and this one refusing to guess, is the invariant the test holds.
 */
internal fun storedSectionEmoji(touched: Boolean, picked: String, stored: String?): String? =
    if (touched) picked else stored

/** What the line under the section field says, when it says anything. */
internal sealed interface SectionNote {
    /** The name implies a section other than the one shown. */
    data class FromName(val category: ProductCategory) : SectionNote

    /** The name is typed and the dictionary has never met it. */
    data object NameUnknown : SectionNote
}

/**
 * Which note belongs under the section field.
 *
 * A rule small enough to be tempting to inline and easy to break by accident,
 * so it lives out here where a test can hold it still. `suggestion` is null both
 * before anything is typed and when the dictionary has nothing to say, which is
 * why the name is needed to tell those two apart.
 */
internal fun sectionNoteFor(
    name: String,
    selectedEmoji: String,
    suggestion: ProductCategory?,
): SectionNote? = when {
    // Only when the two disagree: when the field already shows what the name
    // implies, repeating it is noise.
    suggestion != null ->
        SectionNote.FromName(suggestion).takeIf { suggestion.emoji != selectedEmoji.trim() }
    // Said out loud because nothing else in the form says it: the section stayed
    // empty because the name means nothing to the dictionary, and it will stay
    // empty next time and on the other phone too. This is how the gaps get
    // noticed here rather than in the shop.
    name.isNotBlank() -> SectionNote.NameUnknown
    else -> null
}
