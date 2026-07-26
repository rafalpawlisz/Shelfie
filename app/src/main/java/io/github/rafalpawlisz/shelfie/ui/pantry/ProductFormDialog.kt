package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.ui.scanBarcode

/**
 * Shared add/edit product form, shown as a full-screen dialog: the form has
 * enough fields that a centered card felt cramped and the keyboard covered the
 * confirm button. The confirm action lives in the top bar so it stays reachable
 * above the keyboard; [onArchive] (active) / [onRestore] (archived) render at the
 * bottom of the form. [stateKey] resets the fields when the edited product changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        // The user's intent, not a snapshot: reporting the staged list whole
        // would let a save wipe barcodes that arrived (from the household)
        // while the form was open, since they look like removals.
        addedBarcodes: List<String>,
        removedBarcodes: List<String>,
    ) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    initialQuantity: Int = 0,
    initialUnit: String? = null,
    initialMinQuantity: Int? = null,
    initialNotes: String? = null,
    initialEmoji: String? = null,
    initialBarcodes: List<String> = emptyList(),
    stateKey: Any? = null,
    // Add mode: focus the (required) name field right away so typing can start.
    autoFocusName: Boolean = false,
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
    // Codes are staged locally and committed with the product on confirm
    // (a new product has no id to attach them to until it is saved).
    var barcodes by rememberSaveable(
        stateKey,
        stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it }),
    ) { mutableStateOf(initialBarcodes) }

    val quantity = quantityText.toIntOrNull()
    val minQuantity = minQuantityText.trim().ifBlank { null }?.toIntOrNull()
    val minQuantityValid = minQuantityText.isBlank() || (minQuantity != null && minQuantity >= 0)
    val isValid = name.isNotBlank() && quantity != null && quantity >= 0 && minQuantityValid

    Dialog(
        onDismissRequest = onDismiss,
        // Edge-to-edge so the dialog window reports IME insets; imePadding() below
        // then keeps the scrollable form above the keyboard.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Edge-to-edge dialog draws under the status bar; keep its icons legible
        // for the current theme (dark icons on light, light on dark).
        val view = LocalView.current
        val lightStatusBars = !isSystemInDarkTheme()
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = lightStatusBars
            }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.action_close),
                                )
                            }
                        },
                        title = { Text(title) },
                        actions = {
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
                                        barcodes - initialBarcodes.toSet(),
                                        initialBarcodes - barcodes.toSet(),
                                    )
                                },
                            ) {
                                Text(confirmLabel)
                            }
                        },
                    )
                },
            ) { innerPadding ->
                val context = LocalContext.current
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Emoji is a small leading accessory next to the name.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = emoji,
                            onValueChange = { emoji = it },
                            label = { Text(stringResource(R.string.product_emoji_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.3f),
                        )
                        val nameFocus = remember { FocusRequester() }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.product_name_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.7f).focusRequester(nameFocus),
                        )
                        if (autoFocusName) {
                            LaunchedEffect(Unit) { nameFocus.requestFocus() }
                        }
                    }
                    // Quantity + unit read as one value ("2 l").
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = quantityText,
                            onValueChange = { quantityText = it },
                            label = { Text(stringResource(R.string.product_quantity_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.4f),
                        )
                        OutlinedTextField(
                            value = unit,
                            onValueChange = { unit = it },
                            label = { Text(stringResource(R.string.product_unit_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.6f),
                        )
                    }
                    OutlinedTextField(
                        value = minQuantityText,
                        onValueChange = { minQuantityText = it },
                        label = { Text(stringResource(R.string.product_min_quantity_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        // Echo the entered unit so the threshold reads in context ("4 l").
                        suffix = if (unit.isNotBlank()) {
                            { Text(unit.trim()) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text(stringResource(R.string.product_notes_label)) },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.product_barcodes_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    barcodes.forEach { code ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = code,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { barcodes = barcodes - code }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription =
                                        stringResource(R.string.cd_remove_barcode, code),
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            scanBarcode(context) { code ->
                                if (code !in barcodes) barcodes = barcodes + code
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.scan_barcode))
                    }
                    // Destructive/restore actions live at the bottom of the form;
                    // confirm and close are in the top bar.
                    if (onArchive != null) {
                        TextButton(
                            onClick = onArchive,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.action_archive))
                        }
                    }
                    if (onRestore != null) {
                        TextButton(
                            onClick = onRestore,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.action_restore))
                        }
                    }
                }
            }
        }
    }
}
