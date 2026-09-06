package dev.yusufaf.wren.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ChildButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RevealValue
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwipeToReveal
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.rememberRevealState
import dev.yusufaf.wren.mailkit.Envelope
import kotlinx.coroutines.launch

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
    onArchive: (String) -> Unit,
    onUndoArchive: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSetFlagged: (String, Boolean) -> Unit,
    onSetUnread: (String, Boolean) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    var actionsFor by remember { mutableStateOf<Envelope?>(null) }

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
                        EnvelopeCard(
                            envelope = envelope,
                            transformationSpec = transformationSpec,
                            listState = listState,
                            onClick = { onOpenMessage(envelope.uid) },
                            onShowActions = { actionsFor = envelope },
                            onArchive = { onArchive(envelope.uid) },
                            onUndoArchive = { onUndoArchive(envelope.uid) },
                        )
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

    MessageActionsDialog(
        envelope = actionsFor,
        onDismiss = { actionsFor = null },
        onOpen = { onOpenMessage(it.uid) },
        onArchive = { onArchive(it.uid) },
        onDelete = { onDelete(it.uid) },
        onSetFlagged = onSetFlagged,
        onSetUnread = onSetUnread,
    )
}

@Composable
private fun TransformingLazyColumnItemScope.EnvelopeCard(
    envelope: Envelope,
    transformationSpec: TransformationSpec,
    listState: TransformingLazyColumnState,
    onClick: () -> Unit,
    onShowActions: () -> Unit,
    onArchive: () -> Unit,
    onUndoArchive: () -> Unit,
) {
    val revealState = rememberRevealState()
    val coroutineScope = rememberCoroutineScope()

    // SwipeToReveal should be reset to covered when scrolling occurs, per the
    // canonical sample — a live undo window keeps running even if this
    // dismisses its on-row undo button; see MailRepository.archive.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && revealState.currentValue != RevealValue.Covered) {
            coroutineScope.launch { revealState.animateTo(RevealValue.Covered) }
        }
    }

    SwipeToReveal(
        revealState = revealState,
        primaryAction = {
            PrimaryActionButton(
                onClick = onArchive,
                icon = { Icon(Icons.Outlined.Check, contentDescription = "Archive") },
                text = { Text("Archive") },
            )
        },
        onSwipePrimaryAction = onArchive,
        secondaryAction = {
            SecondaryActionButton(
                onClick = onShowActions,
                icon = { Icon(Icons.Outlined.MoreVert, contentDescription = "More") },
            )
        },
        undoPrimaryAction = {
            UndoActionButton(onClick = onUndoArchive, text = { Text("Undo") })
        },
        modifier = Modifier
            .transformedHeight(this@EnvelopeCard, transformationSpec)
            .animateItem()
            .graphicsLayer {
                with(transformationSpec) { applyContainerTransformation(scrollProgress) }
                // Needed to disable clipping.
                compositingStrategy = CompositingStrategy.ModulateAlpha
                clip = false
            }
            .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding),
    ) {
        Card(
            onClick = onClick,
            onLongClick = onShowActions,
            onLongClickLabel = "Message actions",
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Archive") { onArchive(); true },
                        CustomAccessibilityAction("More actions") { onShowActions(); true },
                    )
                },
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
}

/**
 * Shared by long-press and the swipe row's "More" button. Archive here has no
 * undo window — that affordance lives on the row itself; this dialog is the
 * catch-all for the four actions two swipe slots can't hold.
 */
@Composable
private fun MessageActionsDialog(
    envelope: Envelope?,
    onDismiss: () -> Unit,
    onOpen: (Envelope) -> Unit,
    onArchive: (Envelope) -> Unit,
    onDelete: (Envelope) -> Unit,
    onSetFlagged: (String, Boolean) -> Unit,
    onSetUnread: (String, Boolean) -> Unit,
) {
    AlertDialog(
        visible = envelope != null,
        onDismissRequest = onDismiss,
        title = { Text(envelope?.subject ?: "") },
    ) {
        val current = envelope ?: return@AlertDialog
        item {
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDismiss(); onOpen(current) },
                icon = { Icon(Icons.Outlined.Email, contentDescription = null) },
                label = { Text("Open") },
            )
        }
        item {
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDismiss(); onArchive(current) },
                icon = { Icon(Icons.Outlined.Check, contentDescription = null) },
                label = { Text("Archive") },
            )
        }
        item {
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDismiss(); onDelete(current) },
                icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                label = { Text("Delete") },
            )
        }
        item {
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDismiss(); onSetFlagged(current.uid, !current.flagged) },
                icon = { Icon(Icons.Outlined.Star, contentDescription = null) },
                label = { Text(if (current.flagged) "Unflag" else "Flag") },
            )
        }
        item {
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDismiss(); onSetUnread(current.uid, true) },
                icon = { Icon(Icons.Outlined.Email, contentDescription = null) },
                label = { Text("Mark unread") },
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
