package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDatePickerState
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
import io.github.rafalpawlisz.shelfie.emoji.CategorySuggester
import io.github.rafalpawlisz.shelfie.model.ProductCategory
import io.github.rafalpawlisz.shelfie.ui.scanBarcode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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
        expiresOn: String?,
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
    initialExpiresOn: String? = null,
    initialBarcodes: List<String> = emptyList(),
    stateKey: Any? = null,
    // Add mode: focus the (required) name field right away so typing can start.
    autoFocusName: Boolean = false,
    // Whether the section may follow the typed name until the field is touched.
    // True while creating a product (the suggestion is the whole convenience),
    // false when editing an existing one: there the section is already somebody's
    // answer — including "no section" — and a save must not quietly replace it.
    suggestSection: Boolean = true,
    // Asked on every keystroke whether the name is already taken; a conflict is
    // shown under the field and blocks the save. Default: nothing to collide
    // with (the picker's path deliberately reuses the product it finds).
    nameConflictOf: (String) -> ProductNameConflict? = { null },
    onArchive: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
    // Permanent deletion; the caller passes it only when the product is
    // archived and no list refers to it.
    onDelete: (() -> Unit)? = null,
) {
    var confirmDelete by rememberSaveable(stateKey) { mutableStateOf(false) }
    var name by rememberSaveable(stateKey) { mutableStateOf(initialName) }
    var quantityText by rememberSaveable(stateKey) { mutableStateOf(initialQuantity.toString()) }
    var unit by rememberSaveable(stateKey) { mutableStateOf(initialUnit.orEmpty()) }
    var minQuantityText by rememberSaveable(stateKey) {
        mutableStateOf(initialMinQuantity?.toString().orEmpty())
    }
    var notes by rememberSaveable(stateKey) { mutableStateOf(initialNotes.orEmpty()) }
    // "yyyy-MM-dd" or empty; only ever set by the date picker, so the field
    // cannot hold something that is not a date.
    var expiresOn by rememberSaveable(stateKey) { mutableStateOf(initialExpiresOn.orEmpty()) }
    // The stored value is the section's emoji (see ProductCategory); rows from
    // before sections existed may carry an arbitrary emoji, which is shown
    // as-is until this form assigns a real section.
    var emoji by rememberSaveable(stateKey) { mutableStateOf(initialEmoji.orEmpty()) }
    // Counts as touched when the product arrives with a section — and, in edit
    // mode, always: a stored blank there means "no section", an answer as
    // deliberate as any other. Treating it as "not answered yet" is how a plain
    // Save on an unrelated field (bumping the quantity) used to hand the product
    // a section from its name, which now also moves it on the shopping list.
    var emojiTouched by rememberSaveable(stateKey) {
        mutableStateOf(!suggestSection || !initialEmoji.isNullOrBlank())
    }
    // Codes are staged locally and committed with the product on confirm
    // (a new product has no id to attach them to until it is saved).
    var barcodes by rememberSaveable(
        stateKey,
        stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it }),
    ) { mutableStateOf(initialBarcodes) }

    // What the name implies, computed once per name rather than per keystroke in
    // every field: the picker field needs it for the note under itself, and an
    // untouched section field follows it.
    val suggestedSection = remember(name) { CategorySuggester.suggest(name) }

    // The section fills itself in from the name while the field is untouched.
    // Suggesting is deliberately not written into `emoji` — that keeps "the user
    // picked this" and "we guessed this" separable across recompositions.
    val shownEmoji = if (emojiTouched) emoji else suggestedSection?.emoji.orEmpty()

    val quantity = quantityText.toIntOrNull()
    val minQuantity = minQuantityText.trim().ifBlank { null }?.toIntOrNull()
    val minQuantityValid = minQuantityText.isBlank() || (minQuantity != null && minQuantity >= 0)
    val nameConflict = nameConflictOf(name)
    val isValid = name.isNotBlank() && quantity != null && quantity >= 0 &&
        minQuantityValid && nameConflict == null

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
                                        shownEmoji.trim().ifBlank { null },
                                        expiresOn.ifBlank { null },
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
                    val nameFocus = remember { FocusRequester() }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.product_name_label)) },
                        singleLine = true,
                        isError = nameConflict != null,
                        supportingText = nameConflict?.let { conflict ->
                            {
                                Text(
                                    stringResource(
                                        when (conflict) {
                                            ProductNameConflict.ACTIVE ->
                                                R.string.product_name_taken
                                            ProductNameConflict.ARCHIVED ->
                                                R.string.product_name_in_archive
                                        },
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                    )
                    if (autoFocusName) {
                        LaunchedEffect(Unit) { nameFocus.requestFocus() }
                    }
                    // The store section: a closed list, not a text field — its
                    // whole point is that both phones sort the same aisle the
                    // same way. While untouched it follows the typed name.
                    SectionPickerField(
                        name = name,
                        selectedEmoji = shownEmoji,
                        // Below the box, not inside the field: the field carries
                        // the dropdown's anchor, and Material measures a
                        // supportingText inside that node — which pushed the
                        // opened menu away from the input by the note's height.
                        // What the name implies, so a pick that disagrees with
                        // it says so under the field. Nothing acts on this — it
                        // is the answer to "why is milk in the fish aisle?",
                        // which the closed field otherwise hides.
                        suggestion = suggestedSection,
                        onPick = { category ->
                            emojiTouched = true
                            emoji = category?.emoji.orEmpty()
                        },
                    )
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
                    // Optional and never guessed: for most of the pantry there
                    // is nothing worth writing down, and the value is in the
                    // rarely touched things at the back of the cupboard.
                    ExpiryField(
                        value = expiresOn,
                        onPick = { expiresOn = it },
                        onClear = { expiresOn = "" },
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
                    // Offered only for an archived product no list refers to —
                    // the caller decides that; here it only asks first, because
                    // nothing brings this back.
                    if (onDelete != null) {
                        TextButton(
                            onClick = { confirmDelete = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.action_delete_forever))
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_product_title)) },
            text = { Text(stringResource(R.string.delete_product_message, initialName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.action_delete_forever))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}


/**
 * The best-before field: read-only, opened by tapping, cleared by the trailing
 * button. The field itself still refuses free typing — everything downstream
 * sorts this value as text, so it must never come to hold "30.02" or a
 * two-digit year. The dialog opening on its keyboard is not a softening of
 * that: the picker parses what is typed and answers with a real date or with
 * nothing, and only a real date can be confirmed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiryField(value: String, onPick: (String) -> Unit, onClear: () -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    // A read-only field ignores clicks on itself, so the tap is read from its
    // interaction source instead of wrapping the field in a clickable box —
    // that box would swallow the trailing button's own clicks.
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) showPicker = true
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.product_expires_label)) },
        placeholder = { Text(stringResource(R.string.product_expires_placeholder)) },
        trailingIcon = if (value.isNotBlank()) {
            {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.cd_clear_expiry),
                    )
                }
            }
        } else {
            null
        },
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth(),
    )
    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value.toEpochMillisOrNull(),
            // Opens on the keyboard, not on the calendar. A best-before date is
            // read off a package and copied, and paging a calendar ten months
            // forward to arrive at a date already in front of you is the wrong
            // instrument for that. The mode toggle keeps the calendar one tap
            // away for the times the date is being guessed instead.
            initialDisplayMode = DisplayMode.Input,
            yearRange = expiryYearRange(value),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    // Half a typed date, or an impossible one, leaves the picker
                    // answering null. Without this the button would look live
                    // and then quietly do nothing.
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let { onPick(it.toIsoDate()) }
                        showPicker = false
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

/**
 * The years the picker will offer. Short on purpose — a best-before date is
 * months or a few years out, and the year grid behind the calendar's header is
 * a scroll worth keeping brief — but always stretched to hold the date already
 * stored, because a selection outside the range makes the picker throw instead
 * of clamping.
 */
internal fun expiryYearRange(stored: String): IntRange {
    val thisYear = LocalDate.now().year
    val storedYear = runCatching { LocalDate.parse(stored).year }.getOrNull() ?: thisYear
    return minOf(thisYear - 1, storedYear)..maxOf(thisYear + 15, storedYear)
}

// The picker speaks in UTC-midnight millis; the stored value is a plain date.
// Both conversions pin the zone to UTC, so a date never shifts a day on the way
// through.
private fun String.toEpochMillisOrNull(): Long? = runCatching {
    LocalDate.parse(this).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()

private fun Long.toIsoDate(): String =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate().toString()

