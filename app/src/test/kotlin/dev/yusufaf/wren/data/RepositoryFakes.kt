@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.yusufaf.wren.data

import com.fsck.k9.mail.ConnectionSecurity
import dev.yusufaf.wren.account.Account
import dev.yusufaf.wren.mailkit.Envelope
import dev.yusufaf.wren.mailkit.MailOperations
import dev.yusufaf.wren.mailkit.MessageDetail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime

internal val TEST_ACCOUNT = Account(
    host = "localhost",
    port = 3143,
    security = ConnectionSecurity.NONE,
    username = "wren",
    password = "secret",
)

/** In-memory stand-in for Room's generated [InboxDao]. */
internal class FakeInboxDao : InboxDao {
    val rows = mutableMapOf<String, CachedEnvelope>()
    private val flow = MutableStateFlow<List<CachedEnvelope>>(emptyList())

    override fun envelopes(): Flow<List<CachedEnvelope>> = flow

    override suspend fun clear() {
        rows.clear()
        publish()
    }

    override suspend fun insertAll(items: List<CachedEnvelope>) {
        items.forEach { rows[it.uid] = it }
        publish()
    }

    override suspend fun setUnread(uid: String, unread: Boolean) {
        rows[uid]?.let { rows[uid] = it.copy(unread = unread) }
        publish()
    }

    override suspend fun setFlagged(uid: String, flagged: Boolean) {
        rows[uid]?.let { rows[uid] = it.copy(flagged = flagged) }
        publish()
    }

    override suspend fun remove(uid: String) {
        rows.remove(uid)
        publish()
    }

    private fun publish() {
        flow.value = rows.values.sortedBy { it.position }
    }
}

/** In-memory stand-in for Room's generated [PendingOpDao]. */
internal class FakePendingOpDao : PendingOpDao {
    private var nextId = 1L
    val ops = mutableListOf<PendingOp>()

    override suspend fun due(now: Long): List<PendingOp> =
        ops.filter { it.notBeforeMs <= now }.sortedBy { it.id }

    override suspend fun insert(op: PendingOp) {
        ops.add(op.copy(id = nextId++))
    }

    override suspend fun delete(id: Long) {
        ops.removeAll { it.id == id }
    }

    override suspend fun deleteByUid(uid: String) {
        ops.removeAll { it.uid == uid }
    }
}

/**
 * Records every call instead of touching a real IMAP connection. A triage op
 * on a uid in [failures] throws that exception; [attempted] records every
 * triage call, while the per-op lists only hold the ones that succeeded.
 */
internal class FakeMailOperations : MailOperations {
    val archived = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val flagged = mutableListOf<Pair<String, Boolean>>()
    val unread = mutableListOf<Pair<String, Boolean>>()

    val failures = mutableMapOf<String, Throwable>()
    val attempted = mutableListOf<String>()

    var inbox: List<Envelope> = emptyList()
    var fetchFailure: Throwable? = null
    var fetchCalls = 0

    override suspend fun checkSettings(account: Account) = Unit
    override suspend fun releaseConnections() = Unit

    override suspend fun fetchInbox(account: Account, limit: Int): List<Envelope> {
        fetchCalls++
        fetchFailure?.let { throw it }
        return inbox
    }

    override suspend fun fetchMessage(account: Account, uid: String): MessageDetail =
        error("not exercised by these tests")

    override suspend fun setUnread(account: Account, uid: String, unread: Boolean) {
        attempt(uid)
        this.unread.add(uid to unread)
    }

    override suspend fun setFlagged(account: Account, uid: String, flagged: Boolean) {
        attempt(uid)
        this.flagged.add(uid to flagged)
    }

    override suspend fun deleteMessage(account: Account, uid: String) {
        attempt(uid)
        deleted.add(uid)
    }

    override suspend fun archiveMessage(account: Account, uid: String) {
        attempt(uid)
        archived.add(uid)
    }

    private fun attempt(uid: String) {
        attempted.add(uid)
        failures[uid]?.let { throw it }
    }
}

/**
 * [MailRepository.archive]'s undo window is stamped and checked against
 * [MailRepository]'s injected clock — pointed here at the TestScope's virtual
 * clock so it agrees with `delay()`'s virtual time under [runTest], rather
 * than real wall time that barely moves while the test runs.
 */
internal fun TestScope.newRepository(
    inboxDao: InboxDao,
    pendingOpDao: PendingOpDao,
    mail: MailOperations,
): MailRepository = MailRepository(inboxDao, pendingOpDao, mail, this, now = { currentTime })

internal fun seedRow(inboxDao: FakeInboxDao, uid: String) {
    inboxDao.rows[uid] = CachedEnvelope(
        uid = uid,
        sender = "sender",
        subject = "subject",
        date = "date",
        unread = true,
        flagged = false,
        position = 0,
    )
}
