package dev.yusufaf.wren.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
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
import dev.yusufaf.wren.account.Account
import dev.yusufaf.wren.mailkit.MailService
import dev.yusufaf.wren.mailkit.MessageDetail
import kotlinx.coroutines.launch

private sealed interface MessageState {
    data object Loading : MessageState
    data class Failed(val message: String) : MessageState
    data class Ready(val detail: MessageDetail) : MessageState
}

/**
 * Plain-text message view with the v1 triage actions. Opening the message
 * marks it read on the server; archive/delete/mark-unread run the IMAP
 * operation and then leave the screen via [onDone].
 */
@Composable
fun MessageScreen(
    account: Account,
    uid: String,
    mailService: MailService,
    onDone: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<MessageState>(MessageState.Loading) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uid) {
        state = try {
            MessageState.Ready(mailService.fetchMessage(account, uid))
        } catch (e: Exception) {
            MessageState.Failed(e.message ?: e.toString())
        }
    }

    fun runAction(leaveAfter: Boolean, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        actionError = null
        scope.launch {
            try {
                action()
                if (leaveAfter) onDone() else busy = false
            } catch (e: Exception) {
                actionError = e.message ?: e.toString()
                busy = false
            }
        }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val current = state) {
                is MessageState.Loading -> item {
                    BodyText("Loading…", transformationSpec)
                }

                is MessageState.Failed -> item {
                    BodyText(current.message, transformationSpec)
                }

                is MessageState.Ready -> {
                    val detail = current.detail
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
                            Text(detail.sender)
                        }
                    }
                    item {
                        BodyText(detail.subject, transformationSpec, MaterialTheme.typography.titleSmall)
                    }
                    item {
                        BodyText(
                            detail.date,
                            transformationSpec,
                            MaterialTheme.typography.labelSmall,
                        )
                    }
                    item {
                        BodyText(detail.body, transformationSpec)
                    }
                    actionError?.let { message ->
                        item { BodyText("Error: $message", transformationSpec) }
                    }
                    item {
                        ActionButton("Archive", transformationSpec, busy) {
                            runAction(leaveAfter = true) { mailService.archiveMessage(account, uid) }
                        }
                    }
                    item {
                        ActionButton("Delete", transformationSpec, busy) {
                            runAction(leaveAfter = true) { mailService.deleteMessage(account, uid) }
                        }
                    }
                    item {
                        ActionButton(
                            if (detail.flagged) "Unflag" else "Flag",
                            transformationSpec,
                            busy,
                        ) {
                            runAction(leaveAfter = false) {
                                mailService.setFlagged(account, uid, !detail.flagged)
                                state = MessageState.Ready(detail.copy(flagged = !detail.flagged))
                            }
                        }
                    }
                    item {
                        ActionButton("Mark unread", transformationSpec, busy) {
                            runAction(leaveAfter = true) { mailService.setUnread(account, uid, true) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.BodyText(
    text: String,
    transformationSpec: TransformationSpec,
    style: androidx.compose.ui.text.TextStyle? = null,
) {
    Text(
        text = text,
        style = style ?: MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .minimumVerticalContentPadding(ListHeaderDefaults.minimumBottomListContentPadding)
            .transformedHeight(this, transformationSpec),
    )
}

@Composable
private fun TransformingLazyColumnItemScope.ActionButton(
    label: String,
    transformationSpec: TransformationSpec,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding)
            .transformedHeight(this, transformationSpec),
        transformation = SurfaceTransformation(transformationSpec),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
