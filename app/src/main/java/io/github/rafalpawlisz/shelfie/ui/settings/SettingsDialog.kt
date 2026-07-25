package io.github.rafalpawlisz.shelfie.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.data.AuthUser

/**
 * Minimal settings surface. Today it's the account section (Google sign-in
 * for the coming household sync); designed to grow (e.g. a dynamic-color
 * toggle) without changing its entry point.
 */
@Composable
fun SettingsDialog(
    user: AuthUser?,
    onSignIn: () -> Unit,
    onSignInWithEmail: (email: String, password: String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
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
                    OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sign_out))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
