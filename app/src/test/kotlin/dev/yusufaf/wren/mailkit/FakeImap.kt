package dev.yusufaf.wren.mailkit

import com.fsck.k9.mail.BodyFactory
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageRetrievalListener
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.oauth.OAuth2TokenProvider
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import com.fsck.k9.mail.store.imap.FetchListener
import com.fsck.k9.mail.store.imap.FolderListItem
import com.fsck.k9.mail.store.imap.ImapFolder
import com.fsck.k9.mail.store.imap.ImapMessage
import com.fsck.k9.mail.store.imap.ImapStore
import com.fsck.k9.mail.store.imap.ImapStoreConfig
import com.fsck.k9.mail.store.imap.ImapStoreFactory
import com.fsck.k9.mail.store.imap.OpenMode
import dev.yusufaf.wren.account.Account
import java.net.Socket
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import net.thunderbird.core.common.mail.Flag

internal const val INBOX = "INBOX"
internal const val ARCHIVE = "Archive"

internal val TEST_ACCOUNT = Account(
    host = "localhost",
    port = 3143,
    security = ConnectionSecurity.NONE,
    username = "wren",
    password = "secret",
)

internal val OTHER_ACCOUNT = TEST_ACCOUNT.copy(username = "other")

/**
 * MailService never opens a socket in these tests: the injected store factory
 * replaces everything downstream of it, and the factory only passes this
 * through. Throwing makes an accidental real connection attempt loud.
 */
internal object FakeSocketFactory : TrustedSocketFactory {
    override fun createSocket(
        socket: Socket?,
        host: String,
        port: Int,
        clientCertificateAlias: String?,
    ): Socket = throw UnsupportedOperationException("MailService tests never open a socket")
}

/**
 * The recorder and hook set for one folder name. [FakeImapStore.getFolder]
 * hands out a fresh [FakeImapFolder] per call — mirroring `RealImapStore`,
 * which also returns a new `RealImapFolder` every time — but all of them share
 * one script, so a test can assert across the folder instances a single
 * MailService call creates.
 *
 * Assign to a hook to make the corresponding IMAP call fail.
 */
internal class FolderScript(val name: String) {
    val existsCalls = AtomicInteger()
    val createCalls = AtomicInteger()
    val openCalls = AtomicInteger()
    val closeCalls = AtomicInteger()
    val moveCalls = AtomicInteger()
    val setFlagsCalls = AtomicInteger()

    @Volatile
    var folderExists: Boolean = true

    /** What [FakeImapFolder.messageCount] reports; 0 makes fetchInbox a no-op. */
    @Volatile
    var messageCount: Int = 0

    @Volatile
    var onExists: () -> Unit = {}

    @Volatile
    var onCreate: () -> Unit = {}

    @Volatile
    var onOpen: (OpenMode) -> Unit = {}

    @Volatile
    var onMove: (ImapFolder) -> Unit = {}
}

internal class FakeImapFolder(private val script: FolderScript) : ImapFolder {
    override val serverId: String get() = script.name

    override var mode: OpenMode? = null
        private set

    override val messageCount: Int get() = script.messageCount
    override val isOpen: Boolean get() = mode != null

    override fun exists(): Boolean {
        script.existsCalls.incrementAndGet()
        script.onExists()
        return script.folderExists
    }

    override fun create(folderType: FolderType): Boolean {
        script.createCalls.incrementAndGet()
        script.onCreate()
        script.folderExists = true
        return true
    }

    override fun open(mode: OpenMode) {
        script.openCalls.incrementAndGet()
        script.onOpen(mode)
        this.mode = mode
    }

    override fun close() {
        script.closeCalls.incrementAndGet()
        mode = null
    }

    override fun getMessage(uid: String): ImapMessage = ImapMessage(uid)

    override fun moveMessages(messages: List<ImapMessage>, folder: ImapFolder): Map<String, String>? {
        script.moveCalls.incrementAndGet()
        script.onMove(folder)
        return null
    }

    override fun setFlags(messages: List<ImapMessage>, flags: Set<Flag>, value: Boolean) {
        script.setFlagsCalls.incrementAndGet()
    }

    override fun deleteMessages(messages: List<ImapMessage>) = Unit

    override fun getMessages(
        start: Int,
        end: Int,
        earliestDate: Date?,
        listener: MessageRetrievalListener<ImapMessage>?,
    ): List<ImapMessage> = emptyList()

    override fun fetch(
        messages: List<ImapMessage>,
        fetchProfile: FetchProfile,
        listener: FetchListener?,
        maxDownloadSize: Int,
    ) = Unit

    // Unused by MailService. Fail loudly rather than return a plausible lie:
    // a test that trips one of these is testing something this fake does not
    // model, and should grow the fake deliberately.
    override fun getUidValidity(): Long? = unsupported("getUidValidity")

    override fun getUidFromMessageId(messageId: String): String? = unsupported("getUidFromMessageId")

    override fun areMoreMessagesAvailable(indexOfOldestMessage: Int, earliestDate: Date?): Boolean =
        unsupported("areMoreMessagesAvailable")

    override fun fetchPart(
        message: ImapMessage,
        part: Part,
        bodyFactory: BodyFactory,
        maxDownloadSize: Int,
    ): Unit = unsupported("fetchPart")

    override fun search(
        queryString: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
    ): List<ImapMessage> = unsupported("search")

    override fun appendMessages(messages: List<Message>): Map<String, String>? = unsupported("appendMessages")

    override fun setFlagsForAllMessages(flags: Set<Flag>, value: Boolean): Unit =
        unsupported("setFlagsForAllMessages")

    override fun copyMessages(messages: List<ImapMessage>, folder: ImapFolder): Map<String, String>? =
        unsupported("copyMessages")

    override fun deleteAllMessages(): Unit = unsupported("deleteAllMessages")

    override fun expunge(): Unit = unsupported("expunge")

    override fun expungeUids(uids: List<String>): Unit = unsupported("expungeUids")

    private fun unsupported(call: String): Nothing =
        throw UnsupportedOperationException("FakeImapFolder.$call is not modelled")
}

internal class FakeImapStore : ImapStore {
    private val scripts = ConcurrentHashMap<String, FolderScript>()
    val closeAllConnectionsCalls = AtomicInteger()

    fun script(name: String): FolderScript = scripts.getOrPut(name) { FolderScript(name) }

    /** New instance per call, exactly like `RealImapStore.getFolder`. */
    override fun getFolder(name: String): ImapFolder = FakeImapFolder(script(name))

    @Volatile
    var onCloseAllConnections: () -> Unit = {}

    override fun closeAllConnections() {
        closeAllConnectionsCalls.incrementAndGet()
        onCloseAllConnections()
    }

    override val combinedPrefix: String? = null

    override fun checkSettings() = Unit

    override fun getFolders(): List<FolderListItem> = emptyList()

    override fun fetchImapPrefix() = Unit
}

internal class RecordingStoreFactory(
    /**
     * Applied to each new store before MailService gets to use it, so a test
     * can arm a hook that has to fire on the very first operation — the store
     * does not exist until then.
     */
    private val configure: (FakeImapStore) -> Unit = {},
) : ImapStoreFactory {
    val stores = CopyOnWriteArrayList<FakeImapStore>()
    val createCalls: Int get() = stores.size

    override fun create(
        serverSettings: ServerSettings,
        config: ImapStoreConfig,
        trustedSocketFactory: TrustedSocketFactory,
        oauthTokenProvider: OAuth2TokenProvider?,
    ): ImapStore = FakeImapStore().also {
        configure(it)
        stores += it
    }
}
