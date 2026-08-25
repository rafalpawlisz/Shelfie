package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.emoji.CategorySuggester
import io.github.rafalpawlisz.shelfie.model.ShoppingList

@Composable
internal fun ItemEditDialog(
    lists: List<ShoppingList>,
    currentListId: String?,
    unavailableListIds: Set<String>,
    initialAmount: Int?,
    // A one-off's own unit, and null for a product row — which is also how this
    // dialog knows whether to offer the field at all: a product's unit belongs
    // to the product, and is edited in the product form.
    initialUnit: String?,
    isOneOff: Boolean,
    // The name, for the section picker: what the dictionary would answer, and
    // the note under the field when it answers nothing.
    name: String,
    // What is actually stored on the row — null where nobody has picked a
    // section, which is not the same as the section it currently displays.
    initialSectionEmoji: String?,
    initialNote: String?,
    onConfirm: (
        amount: Int?,
        unit: String?,
        note: String?,
        targetListId: String?,
        sectionEmoji: String?,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var amountText by rememberSaveable { mutableStateOf(initialAmount?.toString().orEmpty()) }
    var unitText by rememberSaveable { mutableStateOf(initialUnit.orEmpty()) }
    var noteText by rememberSaveable { mutableStateOf(initialNote.orEmpty()) }
    var targetListId by rememberSaveable { mutableStateOf(currentListId) }
    // Same three states as everywhere else, and the reason for keeping the
    // stored value apart from the displayed one: saving an untouched row must
    // put back exactly what was there, or editing the amount of a line would
    // silently freeze today's guess at its section into an answer.
    val suggestedSection = remember(name) { CategorySuggester.suggest(name) }
    var sectionTouched by rememberSaveable { mutableStateOf(false) }
    var sectionEmoji by rememberSaveable { mutableStateOf(initialSectionEmoji.orEmpty()) }
    val shownSection =
        shownSectionEmoji(sectionTouched, sectionEmoji, initialSectionEmoji, suggestedSection)
    val amount = amountText.trim().toIntOrNull()
    val isValid = amountText.isBlank() || (amount != null && amount > 0)
    val amountFocus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_item)) },
        text = {
            // The section picker made the dialog taller than a small screen
            // with the keyboard up can hold, so the content scrolls.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (lists.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        lists.forEach { list ->
                            FilterChip(
                                selected = list.id == targetListId,
                                // A list that already plans this product is not a
                                // valid move target.
                                enabled = list.id !in unavailableListIds,
                                onClick = { targetListId = list.id },
                                label = { Text(list.name) },
                            )
                        }
                    }
                }
                if (isOneOff) {
                    // Only a one-off's section is editable here; a product row
                    // wears its product's, changed in the product form.
                    SectionPickerField(
                        name = name,
                        selectedEmoji = shownSection,
                        suggestion = suggestedSection,
                        onPick = { category ->
                            sectionTouched = true
                            sectionEmoji = category?.emoji.orEmpty()
                        },
                    )
                    // Amount + unit read as one value ("200 g").
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = amountText,
                            onValueChange = { amountText = it },
                            label = {
                                Text(stringResource(R.string.shopping_amount_label_short))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(0.4f)
                                .focusRequester(amountFocus),
                        )
                        OutlinedTextField(
                            value = unitText,
                            onValueChange = { unitText = it },
                            label = { Text(stringResource(R.string.product_unit_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.6f),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text(stringResource(R.string.shopping_amount_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.focusRequester(amountFocus),
                    )
                }
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(stringResource(R.string.product_notes_label)) },
                    maxLines = 3,
                )
                // The amount is what an edit is usually about — focus it right
                // away, keyboard and all, like the check-off dialog does.
                LaunchedEffect(Unit) { amountFocus.requestFocus() }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    onConfirm(
                        if (amountText.isBlank()) null else amount,
                        unitText.trim().ifBlank { null },
                        noteText.trim().ifBlank { null },
                        targetListId?.takeIf { it != currentListId },
                        // The tested rule: untouched sends back what was there,
                        // guess and all — see storedSectionEmoji for why the
                        // shown value must never travel this way.
                        storedSectionEmoji(sectionTouched, sectionEmoji, initialSectionEmoji),
                    )
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// [allowEmpty]: a blank field is valid and confirms null ("just buy it") when
// editing; the check-off variant requires a number (stock math needs it).
@Composable
internal fun AmountDialog(
    title: String,
    initialAmount: Int?,
    allowEmpty: Boolean,
    onConfirm: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    // TextFieldValue instead of a plain String so the cursor can start after
    // the prefilled amount — one backspace clears it.
    var amountField by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val text = initialAmount?.toString().orEmpty()
        mutableStateOf(TextFieldValue(text, selection = TextRange(text.length)))
    }
    val amountText = amountField.text
    val amount = amountText.trim().toIntOrNull()
    val isValid = if (amountText.isBlank()) allowEmpty else amount != null && amount > 0
    val amountFocus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = amountField,
                onValueChange = { amountField = it },
                label = { Text(stringResource(R.string.shopping_amount_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.focusRequester(amountFocus),
            )
            // The amount is the dialog's only input — focus it right away.
            LaunchedEffect(Unit) { amountFocus.requestFocus() }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onConfirm(if (amountText.isBlank()) null else amount) },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

