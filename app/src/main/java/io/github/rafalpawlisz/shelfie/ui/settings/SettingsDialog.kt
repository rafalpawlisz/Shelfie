package io.github.rafalpawlisz.shelfie.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.rafalpawlisz.shelfie.R
import android.text.format.DateUtils
import io.github.rafalpawlisz.shelfie.data.AuthUser
import io.github.rafalpawlisz.shelfie.data.sync.SyncStatus
import io.github.rafalpawlisz.shelfie.model.Household
import io.github.rafalpawlisz.shelfie.ui.theme.warning

/**
 * Minimal settings surface. Today it's the account section (Google sign-in
 * for the coming household sync); designed to grow (e.g. a dynamic-color
 * toggle) without changing its entry point.
 */
@Composable
fun SettingsDialog(
    user: AuthUser?,
    household: Household?,
    syncStatus: SyncStatus,
    // Whether this device holds pantry data that joining would overwrite.
    hasLocalData: Boolean,
    errorMessage: Int?,
    onSignIn: () -> Unit,
    onSignInWithEmail: (email: String, password: String) -> Unit,
    onSignOut: () -> Unit,
    onCreateHousehold: (name: String) -> Unit,
    onJoinHousehold: (code: String) -> Unit,
    onLeaveHousehold: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Joining an existing household replaces this device's data with the
    // household's (there is no merge — two "Milk" rows with different ids
    // would poison low-stock and barcode lookups). Never do that silently:
    // hold the code until the user confirms.
    var pendingJoinCode by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
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
                    text = stringResource(R.string.settings_account_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (user == null) {
                    Text(stringResource(R.string.settings_signed_out_hint))
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sign_in_with_google))
                    }
                    HorizontalDivider()
                    var email by rememberSaveable { mutableStateOf("") }
                    var password by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.email_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Errors render inside the dialog (a snackbar would sit
                    // behind its scrim), right by the form they concern.
                    if (errorMessage != null) {
                        Text(
                            text = stringResource(errorMessage),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(
                        onClick = { onSignInWithEmail(email, password) },
                        enabled = email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.sign_in_with_email))
                    }
                } else {
                    Text(
                        text = stringResource(
                            R.string.signed_in_as,
                            user.displayName ?: user.email ?: user.uid,
                        ),
                    )
                    user.email?.takeIf { user.displayName != null }?.let { email ->
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { confirmSignOut = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.sign_out))
                    }
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.household_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Signed-in errors are household errors — show them here.
                    if (errorMessage != null) {
                        Text(
                            text = stringResource(errorMessage),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HouseholdSection(
                        household = household,
                        syncStatus = syncStatus,
                        onCreate = onCreateHousehold,
                        onLeave = { confirmLeave = true },
                        onJoin = { code ->
                            // Nothing to lose only when there is neither a
                            // current household nor local data.
                            if (household != null || hasLocalData) {
                                pendingJoinCode = code
                            } else {
                                onJoinHousehold(code)
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out_title)) },
            text = { Text(stringResource(R.string.sign_out_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSignOut()
                        confirmSignOut = false
                    },
                ) {
                    Text(stringResource(R.string.sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (confirmLeave && household != null) {
        val lastMember = household.memberIds.size <= 1
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.household_leave_title)) },
            text = {
                Text(
                    stringResource(
                        // The household disappears with its last member, so
                        // say so instead of implying it waits for them.
                        if (lastMember) {
                            R.string.household_leave_message_last
                        } else {
                            R.string.household_leave_message
                        },
                        household.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onLeaveHousehold()
                        confirmLeave = false
                    },
                ) {
                    Text(stringResource(R.string.household_leave))
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

@Composable
private fun HouseholdSection(
    household: Household?,
    syncStatus: SyncStatus,
    onCreate: (name: String) -> Unit,
    onLeave: () -> Unit,
    onJoin: (code: String) -> Unit,
) {
    if (household == null) {
        Text(stringResource(R.string.household_none_hint))
        var name by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.household_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onCreate(name) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.household_create))
        }
    } else {
        Text(
            text = household.name,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.household_invite_code, household.inviteCode),
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
