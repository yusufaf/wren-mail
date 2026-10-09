package dev.yusufaf.wren.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.yusufaf.wren.mailkit.TrustException
import dev.yusufaf.wren.mailkit.TrustExceptions
import dev.yusufaf.wren.mailkit.toCertificateInfo
import kotlinx.coroutines.launch

/**
 * The certificates the user has chosen to trust, with a way to revoke each.
 * Like MessageScreen it takes its data source directly; the state is local
 * because nothing outside this screen needs it.
 */
@Composable
fun TrustedCertificatesScreen(trustExceptions: TrustExceptions) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<TrustException>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var revoking by remember { mutableStateOf<TrustException?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun load() {
        try {
            entries = trustExceptions.list()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    LaunchedEffect(Unit) { load() }

    ScreenScaffold(scrollState = listState) { contentPadding ->
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
                    Text("Trusted certificates")
                }
            }
            error?.let { message ->
                item { MessageCard(message, transformationSpec) }
            }
            val current = entries
            when {
                current == null -> item { MessageCard("Loading…", transformationSpec) }
                current.isEmpty() -> item { MessageCard("No trusted certificates", transformationSpec) }
                else -> items(current, key = { "${it.host}:${it.port}" }) { entry ->
                    CertificateRow(entry, transformationSpec, enabled = !busy) { revoking = entry }
                }
            }
        }
    }

    val info = remember(revoking?.certificate) { revoking?.certificate?.toCertificateInfo() }
    AlertDialog(
        visible = revoking != null,
        onDismissRequest = { revoking = null },
        title = { Text("Stop trusting?") },
        text = { Text(revoking?.let { "${it.host}:${it.port}" } ?: "") },
    ) {
        // The exit animation still composes this once revoking is null.
        val target = revoking ?: return@AlertDialog
        certificateDetails(info!!)
        item {
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    revoking = null
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            trustExceptions.revoke(target.host, target.port)
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        } finally {
                            // A persist failure still revoked in memory, so the
                            // list has to reflect it either way.
                            load()
                            busy = false
                        }
                    }
                },
                icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                label = { Text("Revoke") },
            )
        }
        item {
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { revoking = null },
                icon = { Icon(Icons.Outlined.Close, contentDescription = null) },
                label = { Text("Cancel") },
            )
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.CertificateRow(
    entry: TrustException,
    transformationSpec: TransformationSpec,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val subject = remember(entry.certificate) { entry.certificate.toCertificateInfo().subject }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding)
            .transformedHeight(this, transformationSpec),
        transformation = SurfaceTransformation(transformationSpec),
    ) {
        Column {
            Text("${entry.host}:${entry.port}", style = MaterialTheme.typography.labelMedium)
            Text(
                subject,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.MessageCard(
    text: String,
    transformationSpec: TransformationSpec,
) {
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding)
            .transformedHeight(this, transformationSpec),
        transformation = SurfaceTransformation(transformationSpec),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
