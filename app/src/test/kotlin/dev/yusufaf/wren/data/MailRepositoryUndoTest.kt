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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val TEST_ACCOUNT = Account(
    host = "localhost",
    port = 3143,
    security = ConnectionSecurity.NONE,
    username = "wren",
    password = "secret",
)

private const val UNDO_WINDOW_MS = 5_000L

/** In-memory stand-in for Room's generated [InboxDao]. */
private class FakeInboxDao : InboxDao {
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
private class FakePendingOpDao : PendingOpDao {
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

/** Records every call instead of touching a real IMAP connection. */
private class FakeMailOperations : MailOperations {
    val archived = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val flagged = mutableListOf<Pair<String, Boolean>>()
    val unread = mutableListOf<Pair<String, Boolean>>()

    override suspend fun checkSettings(account: Account) = Unit
    override suspend fun releaseConnections() = Unit
    override suspend fun fetchInbox(account: Account, limit: Int): List<Envelope> = emptyList()
    override suspend fun fetchMessage(account: Account, uid: String): MessageDetail =
        error("not exercised by these tests")

    override suspend fun setUnread(account: Account, uid: String, unread: Boolean) {
        this.unread.add(uid to unread)
    }

    override suspend fun setFlagged(account: Account, uid: String, flagged: Boolean) {
        this.flagged.add(uid to flagged)
    }

    override suspend fun deleteMessage(account: Account, uid: String) {
        deleted.add(uid)
    }

    override suspend fun archiveMessage(account: Account, uid: String) {
        archived.add(uid)
    }
}

/**
 * [MailRepository.archive]'s undo window is stamped and checked against
 * [MailRepository]'s injected clock — pointed here at the TestScope's virtual
 * clock so it agrees with `delay()`'s virtual time under [runTest], rather
 * than real wall time that barely moves while the test runs.
 */
private fun TestScope.newRepository(
    inboxDao: InboxDao,
    pendingOpDao: PendingOpDao,
    mail: MailOperations,
): MailRepository = MailRepository(inboxDao, pendingOpDao, mail, this, now = { currentTime })

private fun seedRow(inboxDao: FakeInboxDao, uid: String) {
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

class MailRepositoryUndoTest {

    @Test
    fun `archive with a window queues the op and leaves the cache row alone`() = runTest {
        val inboxDao = FakeInboxDao()
        val pendingOpDao = FakePendingOpDao()
        val mail = FakeMailOperations()
        seedRow(inboxDao, "1")
        val repository = newRepository(inboxDao, pendingOpDao, mail)

        repository.archive(TEST_ACCOUNT, "1", UNDO_WINDOW_MS)

        assertTrue(inboxDao.rows.containsKey("1"))
        assertEquals(1, pendingOpDao.ops.size)
        assertEquals(PendingOp.ARCHIVE, pendingOpDao.ops.single().type)
        assertTrue(mail.archived.isEmpty())
    }

    @Test
    fun `undo inside the window cancels the op and never touches the server`() = runTest {
        val inboxDao = FakeInboxDao()
        val pendingOpDao = FakePendingOpDao()
        val mail = FakeMailOperations()
        seedRow(inboxDao, "1")
        val repository = newRepository(inboxDao, pendingOpDao, mail)

        repository.archive(TEST_ACCOUNT, "1", UNDO_WINDOW_MS)
        advanceTimeBy(1_000)
        val undone = repository.undoArchive("1")
        advanceUntilIdle()

        assertTrue(undone)
        assertTrue(inboxDao.rows.containsKey("1"))
        assertTrue(pendingOpDao.ops.isEmpty())
        assertTrue(mail.archived.isEmpty())
    }

    @Test
    fun `letting the window expire removes the row and archives exactly once`() = runTest {
        val inboxDao = FakeInboxDao()
        val pendingOpDao = FakePendingOpDao()
        val mail = FakeMailOperations()
        seedRow(inboxDao, "1")
        val repository = newRepository(inboxDao, pendingOpDao, mail)

        repository.archive(TEST_ACCOUNT, "1", UNDO_WINDOW_MS)
        advanceTimeBy(UNDO_WINDOW_MS + 1)
        advanceUntilIdle()

        assertFalse(inboxDao.rows.containsKey("1"))
        assertEquals(listOf("1"), mail.archived)
        assertTrue(pendingOpDao.ops.isEmpty())
    }

    @Test
    fun `flushPendingOps mid-window sends nothing`() = runTest {
        val inboxDao = FakeInboxDao()
        val pendingOpDao = FakePendingOpDao()
        val mail = FakeMailOperations()
        seedRow(inboxDao, "1")
        val repository = newRepository(inboxDao, pendingOpDao, mail)

        repository.archive(TEST_ACCOUNT, "1", UNDO_WINDOW_MS)
        advanceTimeBy(1_000)
        repository.flushPendingOps(TEST_ACCOUNT)

        assertTrue(mail.archived.isEmpty())
        assertEquals(1, pendingOpDao.ops.size)
    }

    @Test
    fun `undo after expiry is a no-op`() = runTest {
        val inboxDao = FakeInboxDao()
        val pendingOpDao = FakePendingOpDao()
        val mail = FakeMailOperations()
        seedRow(inboxDao, "1")
        val repository = newRepository(inboxDao, pendingOpDao, mail)

        repository.archive(TEST_ACCOUNT, "1", UNDO_WINDOW_MS)
        advanceTimeBy(UNDO_WINDOW_MS + 1)
        advanceUntilIdle()

        val undone = repository.undoArchive("1")

        assertFalse(undone)
        assertFalse(inboxDao.rows.containsKey("1"))
    }

    @Test
    fun `a delete on a pending archive cancels the archive and sends only the delete`() = runTest {
        val inboxDao = FakeInboxDao()
        val pendingOpDao = FakePendingOpDao()
        val mail = FakeMailOperations()
        seedRow(inboxDao, "1")
        val repository = newRepository(inboxDao, pendingOpDao, mail)

        repository.archive(TEST_ACCOUNT, "1", UNDO_WINDOW_MS)
        repository.delete(TEST_ACCOUNT, "1")
        advanceUntilIdle()

        assertEquals(listOf("1"), mail.deleted)
        assertTrue(mail.archived.isEmpty())
        assertFalse(inboxDao.rows.containsKey("1"))
        // The superseded archive's own delayed job must not fire later and
        // double-send: advancing well past the original window confirms it.
        advanceTimeBy(UNDO_WINDOW_MS + 1)
        advanceUntilIdle()
        assertTrue(mail.archived.isEmpty())
    }

    @Test
    fun `archive with no window behaves exactly as before`() = runTest {
        val inboxDao = FakeInboxDao()
        val pendingOpDao = FakePendingOpDao()
        val mail = FakeMailOperations()
        seedRow(inboxDao, "1")
        val repository = newRepository(inboxDao, pendingOpDao, mail)

        repository.archive(TEST_ACCOUNT, "1")

        assertFalse(inboxDao.rows.containsKey("1"))
        assertEquals(listOf("1"), mail.archived)
        assertTrue(pendingOpDao.ops.isEmpty())
    }
}
