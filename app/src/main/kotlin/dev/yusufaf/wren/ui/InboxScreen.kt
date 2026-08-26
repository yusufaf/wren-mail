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

sealed interface InboxState {
    data object Loading : InboxState
    data class Failed(val message: String) : InboxState
    data class Ready(val envelopes: List<Envelope>) : InboxState
}

@Composable
fun InboxScreen(
    state: InboxState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
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
            when (state) {
                is InboxState.Loading -> item {
                    MessageCard("Loading…", transformationSpec)
                }

                is InboxState.Failed -> {
                    item { MessageCard(state.message, transformationSpec) }
                    item { ActionRow("Retry", transformationSpec, onRefresh) }
                }

                is InboxState.Ready -> {
                    if (state.envelopes.isEmpty()) {
                        item { MessageCard("No messages", transformationSpec) }
                    }
                    items(state.envelopes, key = { it.uid }) { envelope ->
                        EnvelopeCard(envelope, transformationSpec)
                    }
                    item { ActionRow("Refresh", transformationSpec, onRefresh) }
                }
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
) {
    Card(
        onClick = { /* Message view arrives in the next PR. */ },
        modifier = Modifier
            .fillMaxWidth()
            .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding)
            .transformedHeight(this, transformationSpec),
        transformation = SurfaceTransformation(transformationSpec),
    ) {
        Text(
            text = envelope.sender,
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
