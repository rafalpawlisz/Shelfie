package io.github.rafalpawlisz.shelfie.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.rafalpawlisz.shelfie.R
import android.content.ClipData
import android.content.Intent
import android.text.format.DateUtils
import kotlinx.coroutines.launch
import io.github.rafalpawlisz.shelfie.data.sync.SyncStatus
import io.github.rafalpawlisz.shelfie.model.Household
import io.github.rafalpawlisz.shelfie.ui.theme.warning

/**
 * Minimal settings surface: the household this device shares its pantry with.
 *
 * There is no account section. The install gets an anonymous identity by
 * itself, and the invite code is what reaches a household from anywhere — so
 * a sign-in would add a screen without adding a capability. Designed to grow
 * (a dynamic-color toggle, say) without changing its entry point.
 */
@Composable
fun SettingsDialog(
    household: Household?,
    syncStatus: SyncStatus,
    // Whether this device holds pantry data that joining would overwrite.
    hasLocalData: Boolean,
    // Code of the household this device last belonged to, if any.
    rememberedInviteCode: String?,
    error: HouseholdError?,
    onCreateHousehold: (name: String) -> Unit,
    onJoinHousehold: (code: String) -> Unit,
    onRenameHousehold: (name: String) -> Unit,
    onLeaveHousehold: (alsoDelete: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Joining an existing household replaces this device's data with the
    // household's (there is no merge — two "Milk" rows with different ids
    // would poison low-stock and barcode lookups). Never do that silently:
    // hold the code until the user confirms.
    var pendingJoinCode by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Same recipe as the product form: without these the IME overlaps the
        // dialog and its content can neither move nor scroll.
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.household_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Errors render inside the dialog (a snackbar would sit behind
                // its scrim) and next to the form they concern — the section is
                // tall enough that a message at the top would be orphaned from
                // the field that caused it.
                ErrorText(error, ErrorSpot.SECTION)
                HouseholdSection(
                    household = household,
                    syncStatus = syncStatus,
                    error = error,
                    rememberedInviteCode = rememberedInviteCode,
                    onCreate = onCreateHousehold,
                    onRename = { renaming = true },
                    onLeave = { confirmLeave = true },
                    onJoin = { code ->
                        // Nothing to lose only when there is neither a current
                        // household nor local data.
                        if (household != null || hasLocalData) {
                            pendingJoinCode = code
                        } else {
                            onJoinHousehold(code)
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )

    if (renaming && household != null) {
        // Cursor after the current name and focus up front — same as the
        // list-name dialog.
        var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(
                TextFieldValue(household.name, selection = TextRange(household.name.length)),
            )
        }
        val nameFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { renaming = false },
            modifier = Modifier.imePadding(),
            properties = DialogProperties(decorFitsSystemWindows = false),
            title = { Text(stringResource(R.string.household_rename_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.household_name_label)) },
                    singleLine = true,
                    modifier = Modifier.focusRequester(nameFocus),
                )
                LaunchedEffect(Unit) { nameFocus.requestFocus() }
            },
            confirmButton = {
                TextButton(
                    enabled = name.text.isNotBlank(),
                    onClick = {
                        onRenameHousehold(name.text)
                        renaming = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (confirmLeave && household != null) {
        val lastMember = household.memberIds.size <= 1
        // Only a sole member can delete the household — the rules refuse it
        // for anyone else, and taking away data someone still uses is not a
        // decision one member gets to make.
        var alsoDelete by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.household_leave_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            when {
                                // Deleting is irreversible, so say what goes
                                // and what stays instead of repeating the
                                // recovery promise that no longer holds.
                                lastMember && alsoDelete ->
                                    R.string.household_leave_message_delete
                                // An emptied household is kept, so the last
                                // member is told their data stays recoverable
                                // — and both cases get the code, which is
                                // invisible after leaving.
                                lastMember -> R.string.household_leave_message_last
                                else -> R.string.household_leave_message
                            },
                            household.name,
                            household.inviteCode,
                        ),
                    )
                    if (lastMember) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { alsoDelete = !alsoDelete },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = alsoDelete, onCheckedChange = { alsoDelete = it })
                            Text(stringResource(R.string.household_leave_delete_option))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onLeaveHousehold(lastMember && alsoDelete)
                        confirmLeave = false
                    },
                ) {
                    Text(
                        stringResource(
                            if (lastMember && alsoDelete) {
                                R.string.household_leave_and_delete
                            } else {
                                R.string.household_leave
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val pendingCode = pendingJoinCode
    if (pendingCode != null) {
        AlertDialog(
            onDismissRequest = { pendingJoinCode = null },
            title = {
                Text(
                    stringResource(
                        if (household != null) {
                            R.string.household_switch_title
                        } else {
                            R.string.household_join_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    if (household != null) {
                        stringResource(R.string.household_switch_message, household.name)
                    } else {
                        stringResource(R.string.household_join_message)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onJoinHousehold(pendingCode)
                        pendingJoinCode = null
                    },
                ) {
                    Text(
                        stringResource(
                            if (household != null) {
                                R.string.household_switch_confirm
                            } else {
                                R.string.household_join
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingJoinCode = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * The code, with the two ways of getting it off this phone.
 *
 * Since there is no account to fall back on, "write it down" is advice the app
 * has to make actionable: copying puts it somewhere durable, sharing sends it
 * to the person who needs it. Both beat retyping six characters read off a
 * screen — and a mistyped code is indistinguishable from a wrong one.
 */
@Composable
private fun InviteCodeRow(code: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.household_invite_code, code),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                scope.launch {
                    // Android 13+ shows its own "copied" confirmation, and a
                    // dialog cannot host a snackbar anyway.
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText(code, code)),
                    )
                }
            },
        ) {
            Text(stringResource(R.string.action_copy))
        }
        TextButton(
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        context.getString(R.string.household_invite_share_text, code),
                    )
                }
                context.startActivity(Intent.createChooser(intent, null))
            },
        ) {
            Text(stringResource(R.string.action_share))
        }
    }
}

@Composable
private fun SyncStatusRow(status: SyncStatus) {
    when (status) {
        // No session (also right after opening, before the first snapshot).
        SyncStatus.Off -> Unit
        is SyncStatus.Online -> Text(
            text = stringResource(
                R.string.sync_status_synced,
                DateUtils.getRelativeTimeSpanString(status.lastSyncAt).toString(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is SyncStatus.Offline -> Text(
            text = stringResource(R.string.sync_status_offline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.warning,
        )
    }
}

/** Renders [error] only where it belongs, and nothing anywhere else. */
@Composable
private fun ErrorText(error: HouseholdError?, spot: ErrorSpot) {
    if (error == null || error.spot != spot) return
    Text(
        text = stringResource(error.message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun HouseholdSection(
    household: Household?,
    syncStatus: SyncStatus,
    error: HouseholdError?,
    rememberedInviteCode: String?,
    onCreate: (name: String) -> Unit,
    onRename: () -> Unit,
    onLeave: () -> Unit,
    onJoin: (code: String) -> Unit,
) {
    if (household == null) {
        Text(stringResource(R.string.household_none_hint))
        // Whoever was in a household before needs its code, and this is the
        // only place left that knows it.
        rememberedInviteCode?.let { code ->
            Text(
                text = stringResource(R.string.household_last_code, code),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        var name by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.household_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(error, ErrorSpot.CREATE)
        Button(
            onClick = { onCreate(name) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.household_create))
        }
    } else {
        // Name and its rename action on one line: no icon vocabulary to learn,
        // and the section stays compact.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = household.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRename) {
                Text(stringResource(R.string.household_rename))
            }
        }
        InviteCodeRow(household.inviteCode)
        // The code is not just an invitation, it is the only way back in. Say
        // so where the code is, not in a help screen.
        Text(
            text = stringResource(R.string.household_code_keep_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.warning,
        )
        Text(
            text = pluralStringResource(
                R.plurals.household_members,
                household.memberIds.size,
                household.memberIds.size,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SyncStatusRow(syncStatus)
        OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.household_leave))
        }
    }
    // Joining is always available: with no household it's the first join,
    // with one it's a switch (confirmed by the caller).
    var code by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.uppercase() },
        label = { Text(stringResource(R.string.household_code_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(error, ErrorSpot.JOIN)
    OutlinedButton(
        onClick = {
            onJoin(code)
            code = ""
        },
        enabled = code.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.household_join))
    }
}
