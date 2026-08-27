package dev.yusufaf.wren.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ChildButton
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.yusufaf.wren.mailkit.Envelope

/**
 * Cache-first inbox state: [envelopes] is null only until the Room cache emits
 * its first value; [refreshing]/[error] describe the network update layered on
 * top of whatever the cache holds.
 */
data class InboxState(
    val envelopes: List<Envelope>?,
    val refreshing: Boolean,
    val error: String?,
)

@Composable
fun InboxScreen(
    state: InboxState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMessage: (String) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

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
                    Text("Inbox")
                }
            }
            val envelopes = state.envelopes
            when {
                envelopes == null || (envelopes.isEmpty() && state.refreshing) -> item {
                    MessageCard("Loading…", transformationSpec)
                }

                envelopes.isEmpty() -> item {
                    MessageCard(state.error ?: "No messages", transformationSpec)
                }

                else -> {
                    if (state.refreshing) {
                        item { MessageCard("Refreshing…", transformationSpec) }
                    }
                    state.error?.let { error ->
                        item { MessageCard(error, transformationSpec) }
                    }
                    items(envelopes, key = { it.uid }) { envelope ->
                        EnvelopeCard(envelope, transformationSpec) { onOpenMessage(envelope.uid) }
                    }
                }
            }
            if (envelopes != null && !state.refreshing) {
                item { ActionRow(if (state.error != null) "Retry" else "Refresh", transformationSpec, onRefresh) }
            }
            item {
                ActionRow("Account settings", transformationSpec, onOpenSettings)
            }
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.EnvelopeCard(
    envelope: Envelope,
    transformationSpec: TransformationSpec,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding)
            .transformedHeight(this, transformationSpec),
        transformation = SurfaceTransformation(transformationSpec),
    ) {
        Text(
            text = if (envelope.flagged) "⚑ ${envelope.sender}" else envelope.sender,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (envelope.unread) FontWeight.Bold else FontWeight.Normal,
            color = if (envelope.unread) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = envelope.subject,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (envelope.unread) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            text = envelope.date,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

@Composable
private fun TransformingLazyColumnItemScope.ActionRow(
    label: String,
    transformationSpec: TransformationSpec,
    onClick: () -> Unit,
) {
    ChildButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding)
            .transformedHeight(this, transformationSpec),
        transformation = SurfaceTransformation(transformationSpec),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
