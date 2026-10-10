package dev.yusufaf.wren.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.yusufaf.wren.mailkit.CertificateInfo
import dev.yusufaf.wren.mailkit.CertificateProblem
import dev.yusufaf.wren.mailkit.ConnectionFailure
import dev.yusufaf.wren.mailkit.problems
import dev.yusufaf.wren.mailkit.toCertificateInfo
import java.text.DateFormat
import kotlinx.coroutines.delay

/**
 * The prompt shown when a server's certificate failed validation. Trusting is
 * a security decision, so the buttons are labelled rather than icon-only and
 * the reasons it failed, then the certificate's identity, are shown above them.
 * A hostname mismatch needs a second tap, accepted only after the warning has
 * been shown for [TRUST_CONFIRM_DELAY_MILLIS], because it is the one failure
 * that can mean interception; a fast double tap must not trust it.
 */
@Composable
fun CertificateTrustDialog(
    failure: ConnectionFailure.UntrustedCertificate?,
    onTrust: (ConnectionFailure.UntrustedCertificate) -> Unit,
    onDismiss: () -> Unit,
) {
    val info = remember(failure?.certificate) { failure?.certificate?.toCertificateInfo() }
    val problems = remember(failure) { failure?.problems().orEmpty() }
    val mismatch = CertificateProblem.HOSTNAME_MISMATCH in problems
    var confirmingSince by remember(failure) { mutableStateOf<Long?>(null) }
    var armed by remember(failure) { mutableStateOf(false) }
    val confirming = confirmingSince != null
    // Only drives the button's look; trustTap's timestamp check is what actually gates the tap.
    LaunchedEffect(confirmingSince) {
        if (confirmingSince != null) {
            delay(TRUST_CONFIRM_DELAY_MILLIS)
            armed = true
        }
    }
    AlertDialog(
        visible = failure != null,
        onDismissRequest = onDismiss,
        title = { Text("Untrusted certificate") },
        text = { Text(failure?.let { "${it.host}:${it.port}" } ?: "") },
    ) {
        // The exit animation still composes this once failure is null.
        val current = failure ?: return@AlertDialog
        problems.forEach { item { ProblemText(it, current.host) } }
        certificateDetails(info!!)
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (mismatch && confirming) {
                    Text(
                        "This certificate is for a different server. Trusting it lets that server read your mail.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !(mismatch && confirming && !armed),
                    onClick = {
                        val now = SystemClock.uptimeMillis()
                        when (trustTap(mismatch, confirmingSince, now)) {
                            TrustTap.CONFIRM -> confirmingSince = now
                            TrustTap.IGNORE -> Unit
                            TrustTap.TRUST -> onTrust(current)
                        }
                    },
                    icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    label = {
                        Text(
                            when {
                                !mismatch -> "Trust"
                                confirming -> "Confirm trust"
                                else -> "Trust anyway"
                            },
                        )
                    },
                )
            }
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

internal const val TRUST_CONFIRM_DELAY_MILLIS = 1_000L

internal enum class TrustTap { TRUST, CONFIRM, IGNORE }

/** What a tap on the trust button does, given when the mismatch warning was first shown (if it has been). */
internal fun trustTap(mismatch: Boolean, confirmingSince: Long?, now: Long): TrustTap = when {
    !mismatch -> TrustTap.TRUST
    confirmingSince == null -> TrustTap.CONFIRM
    now - confirmingSince < TRUST_CONFIRM_DELAY_MILLIS -> TrustTap.IGNORE
    else -> TrustTap.TRUST
}

internal fun ScalingLazyListScope.certificateDetails(info: CertificateInfo) {
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
    item { DetailText("Subject", info.subject) }
    if (info.names.isNotEmpty()) item { DetailText("Names", info.names.joinToString(", ")) }
    item { DetailText("Issuer", info.issuer) }
    item { DetailText("SHA-256", info.sha256Fingerprint) }
    item { DetailText("Valid", "${dates.format(info.validFrom)} – ${dates.format(info.validUntil)}") }
}

@Composable
private fun ProblemText(problem: CertificateProblem, host: String) {
    val mismatch = problem == CertificateProblem.HOSTNAME_MISMATCH
    Text(
        when (problem) {
            CertificateProblem.HOSTNAME_MISMATCH -> "Not issued for $host"
            CertificateProblem.EXPIRED -> "Expired"
            CertificateProblem.NOT_YET_VALID -> "Not valid yet"
            CertificateProblem.SELF_SIGNED -> "Self-signed"
            CertificateProblem.UNTRUSTED_ISSUER -> "Issuer not trusted"
        },
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (mismatch) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
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
