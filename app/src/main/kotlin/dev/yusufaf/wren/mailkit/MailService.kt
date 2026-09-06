package dev.yusufaf.wren.mailkit

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import com.fsck.k9.mail.store.imap.ImapClientInfo
import com.fsck.k9.mail.store.imap.ImapFolder
import com.fsck.k9.mail.store.imap.ImapStore
import com.fsck.k9.mail.store.imap.ImapStoreConfig
import com.fsck.k9.mail.store.imap.OpenMode
import dev.yusufaf.wren.account.Account
import java.text.DateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Thin IMAP facade over the vendored mail stack. Holds one [ImapStore] per
 * [Account], reused across calls so the underlying connection pool
 * ([com.fsck.k9.mail.store.imap.RealImapStore]) actually gets to pool
 * connections instead of paying a fresh TCP+TLS+LOGIN for every operation.
 * [storeMutex] serializes access so a UI call and [SyncWorker]'s refresh
 * can't race on the same store.
 */
class MailService(private val socketFactory: TrustedSocketFactory) {

    private val storeMutex = Mutex()
    private var cachedAccount: Account? = null
    private var cachedStore: ImapStore? = null
    private var archiveFolderReady = false

    /** Throws MessagingException (or IOException) when settings are wrong. */
    suspend fun checkSettings(account: Account) {
        // Deliberately not cached: this validates credentials before they're
        // saved, so there is nothing yet to reuse the connection for.
        withContext(Dispatchers.IO) {
            val store = buildStore(account)
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

    /**
     * Fetches the plain-text body and marks the message as read. Uses
     * BODY_SANE (capped at [MAX_DOWNLOAD_SIZE]) rather than BODY, which is
     * the only fetch item the server actually honors maxDownloadSize for —
     * BODY pulls the whole RFC822 message uncapped over the radio. Envelope,
     * flags and body are fetched in one round trip.
     */
    suspend fun fetchMessage(account: Account, uid: String): MessageDetail {
        return withInbox(account, OpenMode.READ_WRITE) { folder ->
            val message = folder.getMessage(uid)
            val profile = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(FetchProfile.Item.BODY_SANE)
            }
            folder.fetch(listOf(message), profile, null, MAX_DOWNLOAD_SIZE)

            folder.setFlags(listOf(message), setOf(Flag.SEEN), true)

            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            MessageDetail(
                uid = uid,
                sender = message.senderDisplayName(),
                subject = message.subject ?: "(no subject)",
                date = message.sentDate?.let(dateFormat::format) ?: "",
                body = BodyExtractor.extract(message, MAX_DOWNLOAD_SIZE.toLong())
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

    /**
     * Moves the message to the archive folder, creating the folder if needed.
     * The exists()/create() probe touches its own connection and is only
     * worth paying for once per store lifetime — [archiveFolderReady] skips
     * it (and the connection it would otherwise leave sitting unused in the
     * pool) on every archive after the first. If the move itself fails,
     * [archiveFolderReady] resets so the next attempt re-verifies the folder
     * rather than trusting a check that may now be stale (e.g. the folder
     * was removed server-side after we last confirmed it).
     */
    suspend fun archiveMessage(account: Account, uid: String) {
        withStore(account) { store ->
            val archive = store.getFolder(ARCHIVE_FOLDER)
            if (!archiveFolderReady) {
                if (!archive.exists()) archive.create()
                archiveFolderReady = true
            }
            val inbox = store.getFolder(INBOX_FOLDER)
            try {
                inbox.open(OpenMode.READ_WRITE)
                inbox.moveMessages(listOf(inbox.getMessage(uid)), archive)
            } catch (e: Exception) {
                archiveFolderReady = false
                throw e
            } finally {
                inbox.close()
            }
        }
    }

    /**
     * Closes any pooled connection without discarding the cached store, so
     * an idle socket isn't held open while the app is backgrounded. The next
     * call transparently reconnects.
     */
    suspend fun releaseConnections() {
        withContext(Dispatchers.IO) {
            storeMutex.withLock {
                cachedStore?.closeAllConnections()
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
        return withStore(account) { store ->
            val folder = store.getFolder(INBOX_FOLDER)
            try {
                folder.open(mode)
                block(folder)
            } finally {
                folder.close()
            }
        }
    }

    private suspend fun <T> withStore(account: Account, block: (ImapStore) -> T): T {
        return withContext(Dispatchers.IO) {
            storeMutex.withLock {
                block(storeFor(account))
            }
        }
    }

    /** Must be called under [storeMutex]. Rebuilds the store when the account changes. */
    private fun storeFor(account: Account): ImapStore {
        cachedStore?.let { store ->
            if (cachedAccount == account) return store
            store.closeAllConnections()
        }
        archiveFolderReady = false
        return buildStore(account).also {
            cachedAccount = account
            cachedStore = it
        }
    }

    private fun com.fsck.k9.mail.Message.senderDisplayName(): String {
        return from?.firstOrNull()?.let { address ->
            address.personal?.takeIf { it.isNotBlank() } ?: address.address
        } ?: "(unknown)"
    }

    private fun buildStore(account: Account): ImapStore {
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
        return ImapStore.create(settings, WrenImapConfig, socketFactory, oauthTokenProvider = null)
    }

    private object WrenImapConfig : ImapStoreConfig {
        override val logLabel = "wren"
        override fun isSubscribedFoldersOnly() = false
        override fun isExpungeImmediately() = true
        override fun clientInfo() = ImapClientInfo(appName = "Wren", appVersion = "0.1.0")
    }

    companion object {
        private const val MAX_DOWNLOAD_SIZE = 128 * 1024
        private const val INBOX_WINDOW = 50
        private const val INBOX_FOLDER = "INBOX"
        private const val ARCHIVE_FOLDER = "Archive"
    }
}
