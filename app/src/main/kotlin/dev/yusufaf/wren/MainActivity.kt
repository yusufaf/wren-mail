package dev.yusufaf.wren

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WrenApp()
            }
        }
    }
}

/**
 * Placeholder envelope until the IMAP layer lands (Phase 2).
 */
data class EnvelopePreview(
    val id: Long,
    val sender: String,
    val subject: String,
    val time: String,
    val unread: Boolean,
)

private val placeholderInbox = listOf(
    EnvelopePreview(1, "Wren", "Welcome to Wren", "09:12", unread = true),
    EnvelopePreview(2, "GitHub", "Your build passed", "08:47", unread = true),
    EnvelopePreview(3, "Newsletter", "This week in Wear OS", "07:30", unread = false),
    EnvelopePreview(4, "Alice", "Lunch tomorrow?", "Yesterday", unread = false),
    EnvelopePreview(5, "Bank", "Statement is ready", "Yesterday", unread = false),
)

@Composable
fun WrenApp() {
    AppScaffold(timeText = { TimeText() }) {
        InboxScreen(envelopes = placeholderInbox)
    }
}

@Composable
fun InboxScreen(envelopes: List<EnvelopePreview>) {
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
            items(envelopes, key = { it.id }) { envelope ->
                EnvelopeCard(
                    envelope = envelope,
                    transformationSpec = transformationSpec,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumVerticalContentPadding(
                            CardDefaults.minimumVerticalListContentPadding,
                        )
                        .transformedHeight(this, transformationSpec),
                )
            }
        }
    }
}

@Composable
private fun EnvelopeCard(
    envelope: EnvelopePreview,
    transformationSpec: androidx.wear.compose.material3.lazy.TransformationSpec,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { /* Message view arrives in Phase 3. */ },
        modifier = modifier,
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
            text = envelope.time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
