package dev.yusufaf.wren.spike

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import com.fsck.k9.mail.store.imap.ImapClientInfo
import com.fsck.k9.mail.store.imap.ImapFolder
import com.fsck.k9.mail.store.imap.ImapStore
import com.fsck.k9.mail.store.imap.ImapStoreConfig
import com.fsck.k9.mail.store.imap.OpenMode
import java.net.Socket
import javax.net.ssl.SSLContext
import net.thunderbird.core.common.mail.Flag

data class SpikeEnvelope(
    val uid: String,
    val sender: String,
    val subject: String,
    val date: String,
    val unread: Boolean,
)

data class SpikeReport(
    val messageCount: Int,
    val envelopes: List<SpikeEnvelope>,
    val firstBody: String?,
    val elapsedMs: Long,
)

/**
 * Phase 2 spike: prove the vendored IMAP stack can list INBOX envelopes and
 * fetch one plain-text body. Not production code — no retries, no caching.
 */
object ImapSpike {

    private const val MAX_DOWNLOAD_SIZE = 128 * 1024
    private const val ENVELOPE_WINDOW = 10

    fun run(
        host: String,
        port: Int,
        security: ConnectionSecurity,
        username: String,
        password: String,
    ): SpikeReport {
        val start = System.currentTimeMillis()
        val settings = ServerSettings(
            type = "imap",
            host = host,
            port = port,
            connectionSecurity = security,
            authenticationType = AuthType.PLAIN,
            username = username,
            password = password,
            clientCertificateAlias = null,
        )
        val store = ImapStore.create(settings, SpikeConfig, SpikeSocketFactory, oauthTokenProvider = null)
        var folder: ImapFolder? = null
        try {
            folder = store.getFolder("INBOX")
            folder.open(OpenMode.READ_ONLY)
            val count = folder.messageCount
            val messages = if (count > 0) {
                folder.getMessages(maxOf(1, count - ENVELOPE_WINDOW + 1), count, null, null)
            } else {
                emptyList()
            }

            val envelopeProfile = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
            }
            folder.fetch(messages, envelopeProfile, null, MAX_DOWNLOAD_SIZE)

            val envelopes = messages.map { message ->
                SpikeEnvelope(
                    uid = message.uid,
                    sender = message.from?.firstOrNull()?.toString() ?: "(unknown)",
                    subject = message.subject ?: "(no subject)",
                    date = message.sentDate?.toString() ?: "",
                    unread = !message.isSet(Flag.SEEN),
                )
            }

            val firstBody = messages.lastOrNull()?.let { message ->
                val bodyProfile = FetchProfile().apply { add(FetchProfile.Item.BODY) }
                folder.fetch(listOf(message), bodyProfile, null, MAX_DOWNLOAD_SIZE)
                MessageExtractor.getTextFromPart(message)
            }

            return SpikeReport(
                messageCount = count,
                envelopes = envelopes.asReversed(),
                firstBody = firstBody,
                elapsedMs = System.currentTimeMillis() - start,
            )
        } finally {
            folder?.close()
            store.closeAllConnections()
        }
    }

    private object SpikeConfig : ImapStoreConfig {
        override val logLabel = "wren-spike"
        override fun isSubscribedFoldersOnly() = false
        override fun isExpungeImmediately() = true
        override fun clientInfo() = ImapClientInfo(appName = "Wren", appVersion = "0.1.0")
    }

    private object SpikeSocketFactory : TrustedSocketFactory {
        override fun createSocket(
            socket: Socket?,
            host: String,
            port: Int,
            clientCertificateAlias: String?,
        ): Socket {
            val factory = SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
            return if (socket == null) {
                factory.createSocket(host, port)
            } else {
                factory.createSocket(socket, host, port, true)
            }
        }
    }
}
