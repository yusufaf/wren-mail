package dev.yusufaf.wren.mailkit

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import com.fsck.k9.mail.store.imap.ImapClientInfo
import com.fsck.k9.mail.store.imap.ImapFolder
import com.fsck.k9.mail.store.imap.ImapStore
import com.fsck.k9.mail.store.imap.ImapStoreConfig
import com.fsck.k9.mail.store.imap.ImapStoreFactory
import com.fsck.k9.mail.store.imap.OpenMode
import dev.yusufaf.wren.account.Account
import java.text.DateFormat
import java.util.concurrent.atomic.AtomicReference
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
 * The subset of [MailService] that [dev.yusufaf.wren.data.MailRepository] calls
 * against a live server. Extracted so repository tests can fake the network
 * without a real IMAP connection.
 */
interface MailOperations {
    /** Throws MessagingException (or IOException) when settings are wrong. */
    suspend fun checkSettings(account: Account)
    suspend fun releaseConnections()
    suspend fun fetchInbox(account: Account, limit: Int = 50): List<Envelope>
    suspend fun fetchMessage(account: Account, uid: String): MessageDetail
    suspend fun setUnread(account: Account, uid: String, unread: Boolean)
    suspend fun setFlagged(account: Account, uid: String, flagged: Boolean)
    suspend fun deleteMessage(account: Account, uid: String)
    suspend fun archiveMessage(account: Account, uid: String)
}

/**
 * Thin IMAP facade over the vendored mail stack. Holds one [ImapStore] per
 * [Account], reused across calls so the underlying connection pool
 * ([com.fsck.k9.mail.store.imap.RealImapStore]) actually gets to pool
 * connections instead of paying a fresh TCP+TLS+LOGIN for every operation.
 *
 * [storeMutex] guards the cached store — the lookup, the rebuild when the
 * account changes, and [releaseConnections] — plus a single INBOX probe that
 * warms each new store (see [withStore]). After that, operations run
 * concurrently on the store: a UI call and
 * [dev.yusufaf.wren.sync.SyncWorker]'s refresh each take their own pooled
 * connection, which is the whole point of the pool. Shared mutable state
 * reachable from a store is therefore touched from several threads at once —
 * see the `Wren patch:` comments in `RealImapStore`.
 */
class MailService(
    private val socketFactory: TrustedSocketFactory,
    // ImapStore's companion object implements ImapStoreFactory, so the real
    // store is the default and no production call site passes this. Tests
    // substitute a fake store to exercise MailService without a server.
    private val storeFactory: ImapStoreFactory = ImapStore,
) : MailOperations {

    private val storeMutex = Mutex()
    private var cachedAccount: Account? = null
    private var cachedStore: ImapStore? = null
    // The store whose Archive folder has been confirmed to exist. Holding the
    // store rather than a boolean ties the answer to one store identity: an
    // archive still in flight on a replaced store can neither mark its
    // successor verified nor, by failing, clear the successor's verification
    // (see moveToArchive). Atomic because concurrent operations no longer
    // share the storeMutex's happens-before edge. Two concurrent archives can
    // both see a mismatch and both probe — unreachable through
    // MailRepository, which flushes triage ops one at a time under its own
    // lock, and self-healing via the pending-op queue if it ever happens.
    private val archiveVerifiedStore = AtomicReference<ImapStore?>()

    // The store that has completed its warm-up probe (see withStore). Guarded
    // by storeMutex. Compared by identity, like archiveVerifiedStore, so a
    // rebuilt store is cold without anything having to remember to reset it.
    private var warmedStore: ImapStore? = null

    /** Throws MessagingException (or IOException) when settings are wrong. */
    override suspend fun checkSettings(account: Account) {
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

    // No default here: an override can't redeclare one, it inherits
    // MailOperations' default (50, matching INBOX_WINDOW below).
    override suspend fun fetchInbox(account: Account, limit: Int): List<Envelope> {
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
    override suspend fun fetchMessage(account: Account, uid: String): MessageDetail {
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

    override suspend fun setUnread(account: Account, uid: String, unread: Boolean) {
        setFlag(account, uid, Flag.SEEN, value = !unread)
    }

    override suspend fun setFlagged(account: Account, uid: String, flagged: Boolean) {
        setFlag(account, uid, Flag.FLAGGED, value = flagged)
    }

    override suspend fun deleteMessage(account: Account, uid: String) {
        withInbox(account, OpenMode.READ_WRITE) { folder ->
            folder.deleteMessages(listOf(folder.getMessage(uid)))
        }
    }

    /**
     * Moves the message to the archive folder, creating the folder if needed.
     * The exists()/create() probe touches its own connection and is only
     * worth paying for once per store lifetime — [archiveVerifiedStore] skips
     * it (and the connection it would otherwise leave sitting unused in the
     * pool) on every archive after the first.
     */
    override suspend fun archiveMessage(account: Account, uid: String) {
        withStore(account) { store ->
            val archive = store.getFolder(ARCHIVE_FOLDER)
            if (archiveVerifiedStore.get() !== store) {
                if (!archive.exists()) archive.create()
                archiveVerifiedStore.set(store)
            }
            val inbox = store.getFolder(INBOX_FOLDER)
            try {
                inbox.open(OpenMode.READ_WRITE)
                moveToArchive(store, inbox, uid, archive)
            } finally {
                inbox.close()
            }
        }
    }

    /**
     * The move is the only call in [archiveMessage] that names the archive
     * folder, so it is the only failure that can mean our cached "the folder
     * exists" answer has gone stale (e.g. a NO [TRYCREATE] because the folder
     * was removed server-side). Clearing [archiveVerifiedStore] on an inbox
     * open failure, or any other transient network error, only buys an extra
     * exists()/create() round trip on the retry of a connection that is
     * already struggling. The clear is conditional on [store] still being the
     * verified one: a move that fails on a replaced store says nothing about
     * its successor's archive folder.
     */
    private fun moveToArchive(store: ImapStore, inbox: ImapFolder, uid: String, archive: ImapFolder) {
        try {
            inbox.moveMessages(listOf(inbox.getMessage(uid)), archive)
        } catch (e: Exception) {
            archiveVerifiedStore.compareAndSet(store, null)
            throw e
        }
    }

    /**
     * Closes any pooled connection without discarding the cached store, so
     * an idle socket isn't held open while the app is backgrounded. The next
     * call transparently reconnects.
     *
     * This can now run while an operation is in flight. That operation's
     * connection has been polled out of the pool, so it isn't closed here;
     * the generation bump means it is normally dropped rather than returned to
     * the pool when the operation finishes. `RealImapStore.releaseConnection`
     * checks the generation outside `synchronized(connections)`, so a stale
     * connection can occasionally be pooled anyway; the next `getConnection`
     * NOOPs it and discards it if it is dead.
     */
    override suspend fun releaseConnections() {
        withContext(Dispatchers.IO) {
            storeMutex.withLock {
                cachedStore?.closeAllConnections()
            }
        }
    }

    /**
     * Closes the pool like [releaseConnections], and also discards the cached
     * store. A connection an in-flight operation returns after the close then
     * lands in a store nobody uses again, so the re-pooling race described on
     * [releaseConnections] can't hand it out. Every later operation builds a
     * fresh store whose connections handshake against the current trust state.
     * Called when a trust exception is revoked; an orphaned connection lingers
     * only until the server's idle timeout.
     *
     * `archiveVerifiedStore` is left alone: it is compared by identity, so the
     * discarded store's entry can't match its successor.
     */
    suspend fun resetConnections() {
        withContext(Dispatchers.IO) {
            storeMutex.withLock {
                cachedStore?.closeAllConnections()
                cachedStore = null
                cachedAccount = null
                warmedStore = null
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
            // The lock covers the cached-store lookup and rebuild, plus one
            // INBOX probe on each cold store; the caller's block runs after
            // the lock is released, because serializing the round trip itself
            // would defeat the connection pool. The probe is the first
            // connection open on the store, and RealImapStore writes its
            // path-prefix state unsynchronized while that happens, so
            // overlapping opens can cache a stale combinedPrefix. The probe
            // returns its connection to the pool for the block to reuse. A
            // failed probe throws and leaves the store cold, so the next call
            // probes again.
            val store = storeMutex.withLock {
                storeFor(account).also {
                    if (warmedStore !== it) {
                        warmUp(it)
                        warmedStore = it
                    }
                }
            }
            block(store)
        }
    }

    private fun warmUp(store: ImapStore) {
        store.getFolder(INBOX_FOLDER).exists()
    }

    /** Must be called under [storeMutex]. Rebuilds the store when the account changes. */
    private fun storeFor(account: Account): ImapStore {
        cachedStore?.let { store ->
            if (cachedAccount == account) return store
            store.closeAllConnections()
        }
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
        return storeFactory.create(settings, WrenImapConfig, socketFactory, oauthTokenProvider = null)
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
