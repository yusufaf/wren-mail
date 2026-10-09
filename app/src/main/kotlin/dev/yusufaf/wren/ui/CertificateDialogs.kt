package dev.yusufaf.wren.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.yusufaf.wren.mailkit.CertificateInfo
import dev.yusufaf.wren.mailkit.ConnectionFailure
import dev.yusufaf.wren.mailkit.toCertificateInfo
import java.text.DateFormat

/**
 * The prompt shown when a server's certificate failed validation. Trusting is
 * a security decision, so the buttons are labelled rather than icon-only and
 * the certificate's identity is shown above them.
 */
@Composable
fun CertificateTrustDialog(
    failure: ConnectionFailure.UntrustedCertificate?,
    onTrust: (ConnectionFailure.UntrustedCertificate) -> Unit,
    onDismiss: () -> Unit,
) {
    val info = remember(failure?.certificate) { failure?.certificate?.toCertificateInfo() }
    AlertDialog(
        visible = failure != null,
        onDismissRequest = onDismiss,
        title = { Text("Untrusted certificate") },
        text = { Text(failure?.let { "${it.host}:${it.port}" } ?: "") },
    ) {
        // The exit animation still composes this once failure is null.
        val current = failure ?: return@AlertDialog
        certificateDetails(info!!)
        item {
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onTrust(current) },
                icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                label = { Text("Trust") },
            )
        }
        item {
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
                icon = { Icon(Icons.Outlined.Close, contentDescription = null) },
                label = { Text("Cancel") },
            )
        }
    }
}

internal fun ScalingLazyListScope.certificateDetails(info: CertificateInfo) {
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
    item { DetailText("Subject", info.subject) }
    item { DetailText("Issuer", info.issuer) }
    item { DetailText("SHA-256", info.sha256Fingerprint) }
    item { DetailText("Valid", "${dates.format(info.validFrom)} – ${dates.format(info.validUntil)}") }
}

@Composable
private fun DetailText(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
