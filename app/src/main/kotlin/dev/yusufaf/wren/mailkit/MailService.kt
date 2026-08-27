package dev.yusufaf.wren.mailkit

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import com.fsck.k9.mail.store.imap.ImapClientInfo
import com.fsck.k9.mail.store.imap.ImapFolder
import com.fsck.k9.mail.store.imap.ImapStore
import com.fsck.k9.mail.store.imap.ImapStoreConfig
import com.fsck.k9.mail.store.imap.OpenMode
import dev.yusufaf.wren.account.Account
import java.net.Socket
import java.text.DateFormat
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.core.common.mail.Flag

data class Envelope(
    val uid: String,
    val sender: String,
    val subject: String,
    val date: String,
    val unread: Boolean,
    val flagged: Boolean,
)

data class MessageDetail(
    val uid: String,
    val sender: String,
    val subject: String,
    val date: String,
    val body: String,
    val flagged: Boolean,
)

/**
 * Thin synchronous-IMAP facade over the vendored mail stack. Every call opens
 * its own connection and closes it before returning; no caching yet (Room
 * lands later in Phase 3).
 */
class MailService {

    /** Throws MessagingException (or IOException) when settings are wrong. */
    suspend fun checkSettings(account: Account) {
        withContext(Dispatchers.IO) {
            val store = createStore(account)
            try {
                store.checkSettings()
            } finally {
                store.closeAllConnections()
            }
        }
    }

    suspend fun fetchInbox(account: Account, limit: Int = INBOX_WINDOW): List<Envelope> {
        return withInbox(account, OpenMode.READ_ONLY) { folder ->
            val count = folder.messageCount
            if (count == 0) return@withInbox emptyList()

            val messages = folder.getMessages(maxOf(1, count - limit + 1), count, null, null)
            val profile = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
            }
            folder.fetch(messages, profile, null, MAX_DOWNLOAD_SIZE)

            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            messages.map { message ->
                Envelope(
                    uid = message.uid,
                    sender = message.senderDisplayName(),
                    subject = message.subject ?: "(no subject)",
                    date = message.sentDate?.let(dateFormat::format) ?: "",
                    unread = !message.isSet(Flag.SEEN),
                    flagged = message.isSet(Flag.FLAGGED),
                )
            }.asReversed()
        }
    }

    /** Fetches the plain-text body and marks the message as read. */
    suspend fun fetchMessage(account: Account, uid: String): MessageDetail {
        return withInbox(account, OpenMode.READ_WRITE) { folder ->
            val message = folder.getMessage(uid)
            val envelopeProfile = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
            }
            folder.fetch(listOf(message), envelopeProfile, null, MAX_DOWNLOAD_SIZE)
            val bodyProfile = FetchProfile().apply { add(FetchProfile.Item.BODY) }
            folder.fetch(listOf(message), bodyProfile, null, MAX_DOWNLOAD_SIZE)

            folder.setFlags(listOf(message), setOf(Flag.SEEN), true)

            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            MessageDetail(
                uid = uid,
                sender = message.senderDisplayName(),
                subject = message.subject ?: "(no subject)",
                date = message.sentDate?.let(dateFormat::format) ?: "",
                body = MessageExtractor.getTextFromPart(message, MAX_DOWNLOAD_SIZE.toLong())
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "(no text content)",
                flagged = message.isSet(Flag.FLAGGED),
            )
        }
    }

    suspend fun setUnread(account: Account, uid: String, unread: Boolean) {
        setFlag(account, uid, Flag.SEEN, value = !unread)
    }

    suspend fun setFlagged(account: Account, uid: String, flagged: Boolean) {
        setFlag(account, uid, Flag.FLAGGED, value = flagged)
    }

    suspend fun deleteMessage(account: Account, uid: String) {
        withInbox(account, OpenMode.READ_WRITE) { folder ->
            folder.deleteMessages(listOf(folder.getMessage(uid)))
        }
    }

    /** Moves the message to the archive folder, creating the folder if needed. */
    suspend fun archiveMessage(account: Account, uid: String) {
        withContext(Dispatchers.IO) {
            val store = createStore(account)
            var inbox: ImapFolder? = null
            try {
                val archive = store.getFolder(ARCHIVE_FOLDER)
                if (!archive.exists()) archive.create()
                inbox = store.getFolder(INBOX_FOLDER)
                inbox.open(OpenMode.READ_WRITE)
                inbox.moveMessages(listOf(inbox.getMessage(uid)), archive)
            } finally {
                inbox?.close()
                store.closeAllConnections()
            }
        }
    }

    private suspend fun setFlag(account: Account, uid: String, flag: Flag, value: Boolean) {
        withInbox(account, OpenMode.READ_WRITE) { folder ->
            folder.setFlags(listOf(folder.getMessage(uid)), setOf(flag), value)
        }
    }

    private suspend fun <T> withInbox(
        account: Account,
        mode: OpenMode,
        block: (ImapFolder) -> T,
    ): T {
        return withContext(Dispatchers.IO) {
            val store = createStore(account)
            var folder: ImapFolder? = null
            try {
                folder = store.getFolder(INBOX_FOLDER)
                folder.open(mode)
                block(folder)
            } finally {
                folder?.close()
                store.closeAllConnections()
            }
        }
    }

    private fun com.fsck.k9.mail.Message.senderDisplayName(): String {
        return from?.firstOrNull()?.let { address ->
            address.personal?.takeIf { it.isNotBlank() } ?: address.address
        } ?: "(unknown)"
    }

    private fun createStore(account: Account): ImapStore {
        val settings = ServerSettings(
            type = "imap",
            host = account.host,
            port = account.port,
            connectionSecurity = account.security,
            authenticationType = AuthType.PLAIN,
            username = account.username,
            password = account.password,
            clientCertificateAlias = null,
        )
        return ImapStore.create(settings, WrenImapConfig, SystemTrustSocketFactory, oauthTokenProvider = null)
    }

    private object WrenImapConfig : ImapStoreConfig {
        override val logLabel = "wren"
        override fun isSubscribedFoldersOnly() = false
        override fun isExpungeImmediately() = true
        override fun clientInfo() = ImapClientInfo(appName = "Wren", appVersion = "0.1.0")
    }

    /** TLS sockets validated against the system trust store. */
    private object SystemTrustSocketFactory : TrustedSocketFactory {
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

    companion object {
        private const val MAX_DOWNLOAD_SIZE = 128 * 1024
        private const val INBOX_WINDOW = 50
        private const val INBOX_FOLDER = "INBOX"
        private const val ARCHIVE_FOLDER = "Archive"
    }
}
