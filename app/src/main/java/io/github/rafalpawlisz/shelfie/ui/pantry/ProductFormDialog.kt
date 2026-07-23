package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.rafalpawlisz.shelfie.R

/**
 * Shared add/edit product form. [onArchive] renders a destructive Archive
 * action (active products), [onRestore] a Restore action (archived ones);
 * [stateKey] resets the fields when the edited product changes.
 */
@Composable
fun ProductFormDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int?,
        notes: String?,
        emoji: String?,
    ) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    initialQuantity: Int = 0,
    initialUnit: String? = null,
    initialMinQuantity: Int? = null,
    initialNotes: String? = null,
    initialEmoji: String? = null,
    stateKey: Any? = null,
    onArchive: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
) {
    var name by rememberSaveable(stateKey) { mutableStateOf(initialName) }
    var quantityText by rememberSaveable(stateKey) { mutableStateOf(initialQuantity.toString()) }
    var unit by rememberSaveable(stateKey) { mutableStateOf(initialUnit.orEmpty()) }
    var minQuantityText by rememberSaveable(stateKey) {
        mutableStateOf(initialMinQuantity?.toString().orEmpty())
    }
    var notes by rememberSaveable(stateKey) { mutableStateOf(initialNotes.orEmpty()) }
    var emoji by rememberSaveable(stateKey) { mutableStateOf(initialEmoji.orEmpty()) }

    val quantity = quantityText.toIntOrNull()
    val minQuantity = minQuantityText.trim().ifBlank { null }?.toIntOrNull()
    val minQuantityValid = minQuantityText.isBlank() || (minQuantity != null && minQuantity >= 0)
    val isValid = name.isNotBlank() && quantity != null && quantity >= 0 && minQuantityValid

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.product_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it },
                    label = { Text(stringResource(R.string.product_emoji_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text(stringResource(R.string.product_quantity_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text(stringResource(R.string.product_unit_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = minQuantityText,
                    onValueChange = { minQuantityText = it },
                    label = { Text(stringResource(R.string.product_min_quantity_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.product_notes_label)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (onArchive != null) {
                        TextButton(
                            onClick = onArchive,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(stringResource(R.string.action_archive))
                        }
                    }
                    if (onRestore != null) {
                        TextButton(onClick = onRestore) {
                            Text(stringResource(R.string.action_restore))
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        enabled = isValid,
                        onClick = {
                            onConfirm(
                                name.trim(),
                                quantity ?: 0,
                                unit.trim().ifBlank { null },
                                minQuantity,
                                notes.trim().ifBlank { null },
                                emoji.trim().ifBlank { null },
                            )
                        },
                    ) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}
