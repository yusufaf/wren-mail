package dev.yusufaf.wren

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
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
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.fsck.k9.mail.ConnectionSecurity
import dev.yusufaf.wren.spike.ImapSpike
import dev.yusufaf.wren.spike.SpikeReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * Phase 2 spike configuration: a GreenMail test server on the host machine
 * (10.0.2.2 from the emulator), plain IMAP. Replaced by real account setup in
 * Phase 3.
 */
private object SpikeServer {
    const val HOST = "10.0.2.2"
    const val PORT = 3143
    val SECURITY = ConnectionSecurity.NONE
    const val USERNAME = "wren"
    const val PASSWORD = "secret"
}

sealed interface SpikeState {
    data object Loading : SpikeState
    data class Ready(val report: SpikeReport) : SpikeState
    data class Failed(val message: String) : SpikeState
}

@Composable
fun WrenApp() {
    var state by remember { mutableStateOf<SpikeState>(SpikeState.Loading) }

    LaunchedEffect(Unit) {
        state = withContext(Dispatchers.IO) {
            try {
                SpikeState.Ready(
                    ImapSpike.run(
                        host = SpikeServer.HOST,
                        port = SpikeServer.PORT,
                        security = SpikeServer.SECURITY,
                        username = SpikeServer.USERNAME,
                        password = SpikeServer.PASSWORD,
                    ),
                )
            } catch (e: Exception) {
                SpikeState.Failed(e.toString())
            }
        }
    }

    AppScaffold(timeText = { TimeText() }) {
        InboxScreen(state = state)
    }
}

@Composable
fun InboxScreen(state: SpikeState) {
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
            when (state) {
                is SpikeState.Loading -> item {
                    StatusCard(
                        text = "Connecting to ${SpikeServer.HOST}:${SpikeServer.PORT}…",
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = envelopeModifier(transformationSpec),
                    )
                }

                is SpikeState.Failed -> item {
                    StatusCard(
                        text = state.message,
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = envelopeModifier(transformationSpec),
                    )
                }

                is SpikeState.Ready -> {
                    item {
                        StatusCard(
                            text = "${state.report.messageCount} messages, " +
                                "fetched in ${state.report.elapsedMs} ms",
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = envelopeModifier(transformationSpec),
                        )
                    }
                    items(state.report.envelopes, key = { it.uid }) { envelope ->
                        Card(
                            onClick = { /* Message view arrives in Phase 3. */ },
                            modifier = envelopeModifier(transformationSpec),
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
                    state.report.firstBody?.let { body ->
                        item {
                            StatusCard(
                                text = "Latest body:\n${body.take(280)}",
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = envelopeModifier(transformationSpec),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.envelopeModifier(spec: TransformationSpec): Modifier =
    Modifier
        .fillMaxWidth()
        .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding)
        .transformedHeight(this, spec)

@Composable
private fun StatusCard(
    text: String,
    transformation: SurfaceTransformation,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = {},
        modifier = modifier,
        transformation = transformation,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
