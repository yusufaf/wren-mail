package dev.yusufaf.wren.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.fsck.k9.mail.ConnectionSecurity
import dev.yusufaf.wren.account.Account
import kotlinx.coroutines.launch

/**
 * On-watch account form. Each field opens the system text input; Save
 * validates the settings against the server before persisting.
 */
@Composable
fun AccountSetupScreen(
    initial: Account?,
    onValidateAndSave: suspend (Account) -> String?,
    onSaved: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val scope = rememberCoroutineScope()

    var host by rememberSaveable { mutableStateOf(initial?.host ?: "") }
    var port by rememberSaveable { mutableStateOf(initial?.port ?: 993) }
    var security by rememberSaveable {
        mutableStateOf(initial?.security ?: ConnectionSecurity.SSL_TLS_REQUIRED)
    }
    var username by rememberSaveable { mutableStateOf(initial?.username ?: "") }
    var password by rememberSaveable { mutableStateOf(initial?.password ?: "") }
    var checking by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val hostInput = rememberTextInputLauncher("Server") { host = it.trim() }
    val portInput = rememberTextInputLauncher("Port") { text ->
        text.trim().toIntOrNull()?.let { port = it }
    }
    val usernameInput = rememberTextInputLauncher("Username") { username = it.trim() }
    val passwordInput = rememberTextInputLauncher("Password") { password = it }

    val candidate = Account(host, port, security, username, password)

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(
                onClick = {
                    if (checking || !candidate.isComplete) return@EdgeButton
                    checking = true
                    error = null
                    scope.launch {
                        val failure = onValidateAndSave(candidate)
                        checking = false
                        if (failure == null) onSaved() else error = failure
                    }
                },
                enabled = candidate.isComplete && !checking,
                modifier = Modifier.scrollable(
                    listState,
                    orientation = Orientation.Vertical,
                    reverseDirection = true,
                    overscrollEffect = rememberOverscrollEffect(),
                ),
            ) {
                Text(if (checking) "Checking…" else "Save")
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumVerticalContentPadding(
                            ListHeaderDefaults.minimumTopListContentPadding,
                            ListHeaderDefaults.minimumBottomListContentPadding,
                        )
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text("IMAP account")
                }
            }
            error?.let { message ->
                item {
                    SettingRow(
                        label = "Error",
                        value = message,
                        transformationSpec = transformationSpec,
                        onClick = { error = null },
                    )
                }
            }
            item {
                SettingRow("Server", host.ifBlank { "Not set" }, transformationSpec, hostInput)
            }
            item {
                SettingRow("Port", port.toString(), transformationSpec, portInput)
            }
            item {
                SettingRow(
                    label = "Security",
                    value = when (security) {
                        ConnectionSecurity.SSL_TLS_REQUIRED -> "SSL/TLS"
                        ConnectionSecurity.STARTTLS_REQUIRED -> "STARTTLS"
                        ConnectionSecurity.NONE -> "None"
                    },
                    transformationSpec = transformationSpec,
                ) {
                    val previousDefault = Account.defaultPortFor(security)
                    security = when (security) {
                        ConnectionSecurity.SSL_TLS_REQUIRED -> ConnectionSecurity.STARTTLS_REQUIRED
                        ConnectionSecurity.STARTTLS_REQUIRED -> ConnectionSecurity.NONE
                        ConnectionSecurity.NONE -> ConnectionSecurity.SSL_TLS_REQUIRED
                    }
                    if (port == previousDefault) port = Account.defaultPortFor(security)
                }
            }
            item {
                SettingRow("Username", username.ifBlank { "Not set" }, transformationSpec, usernameInput)
            }
            item {
                SettingRow(
                    label = "Password",
                    value = if (password.isEmpty()) "Not set" else "•".repeat(8),
                    transformationSpec = transformationSpec,
                    onClick = passwordInput,
                )
            }
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.SettingRow(
    label: String,
    value: String,
    transformationSpec: TransformationSpec,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding)
            .transformedHeight(this, transformationSpec),
        transformation = SurfaceTransformation(transformationSpec),
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
