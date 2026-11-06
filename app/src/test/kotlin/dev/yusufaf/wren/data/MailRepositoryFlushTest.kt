@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.yusufaf.wren.data

import com.fsck.k9.mail.AuthenticationFailedException
import dev.yusufaf.wren.mailkit.Envelope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import net.thunderbird.core.common.exception.MessagingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun rejected() = MessagingException("NO [CANNOT] Archive is read-only", true)

private fun connectionLost() = MessagingException("IO Error", IOException("Connection reset"))

private fun loginRejected() = MessagingException(
    "Command: *sensitive*; response: #1# [NO, [ALERT], Please log in via your web browser]",
    true,
)

private fun envelope(uid: String, unread: Boolean = true, flagged: Boolean = false) =
    Envelope(uid, "s", "subj", "d", unread, flagged)

private fun FakePendingOpDao.queued() = ops.sortedBy { it.id }.map { it.uid to it.type }

class MailRepositoryFlushTest {

    private val inboxDao = FakeInboxDao()
    private val pendingOpDao = FakePendingOpDao()
    private val mail = FakeMailOperations()

    @Test
    fun `a rejected op doesn't block later ops for other messages`() = runTest {
        val repository = newRepository(inboxDao, pendingOpDao, mail)
        mail.failures["1"] = rejected()
        pendingOpDao.insert(PendingOp(uid = "1", type = PendingOp.ARCHIVE))
        pendingOpDao.insert(PendingOp(uid = "2", type = PendingOp.DELETE))
        pendingOpDao.insert(PendingOp(uid = "3", type = PendingOp.SET_FLAGGED, value = true))

        repository.flushPendingOps(TEST_ACCOUNT)

        assertEquals(listOf("2"), mail.deleted)
        assertEquals(listOf("3" to true), mail.flagged)
        assertEquals(listOf("1" to PendingOp.ARCHIVE), pendingOpDao.queued())
    }

    @Test
    fun `later ops for a message stay queued behind its rejected op`() = runTest {
        val repository = newRepository(inboxDao, pendingOpDao, mail)
        mail.failures["1"] = rejected()
        pendingOpDao.insert(PendingOp(uid = "1", type = PendingOp.ARCHIVE))
        pendingOpDao.insert(PendingOp(uid = "1", type = PendingOp.SET_FLAGGED, value = true))
        pendingOpDao.insert(PendingOp(uid = "2", type = PendingOp.DELETE))

        repository.flushPendingOps(TEST_ACCOUNT)

        assertEquals(listOf("1", "2"), mail.attempted)
        assertTrue(mail.flagged.isEmpty())
        assertEquals(listOf("2"), mail.deleted)
        assertEquals(
            listOf("1" to PendingOp.ARCHIVE, "1" to PendingOp.SET_FLAGGED),
            pendingOpDao.queued(),
        )
    }

    @Test
    fun `a connection failure stops the flush and keeps every op`() = runTest {
        val repository = newRepository(inboxDao, pendingOpDao, mail)
        val failure = connectionLost()
        mail.failures["1"] = failure
        pendingOpDao.insert(PendingOp(uid = "1", type = PendingOp.DELETE))
        pendingOpDao.insert(PendingOp(uid = "2", type = PendingOp.DELETE))

        val thrown = runCatching { repository.flushPendingOps(TEST_ACCOUNT) }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf("1"), mail.attempted)
        assertEquals(2, pendingOpDao.ops.size)
    }

    @Test
    fun `a rejected login stops the flush and keeps every op`() = runTest {
        val repository = newRepository(inboxDao, pendingOpDao, mail)
        val failure = loginRejected()
        mail.failures["1"] = failure
        pendingOpDao.insert(PendingOp(uid = "1", type = PendingOp.DELETE))
        pendingOpDao.insert(PendingOp(uid = "2", type = PendingOp.DELETE))

        val thrown = runCatching { repository.flushPendingOps(TEST_ACCOUNT) }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf("1"), mail.attempted)
        assertEquals(2, pendingOpDao.ops.size)
    }

    @Test
    fun `cancellation stops the flush instead of counting as a rejection`() = runTest {
        val repository = newRepository(inboxDao, pendingOpDao, mail)
        mail.failures["1"] = CancellationException("cancelled")
        pendingOpDao.insert(PendingOp(uid = "1", type = PendingOp.DELETE))
        pendingOpDao.insert(PendingOp(uid = "2", type = PendingOp.DELETE))

        val thrown = runCatching { repository.flushPendingOps(TEST_ACCOUNT) }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(listOf("1"), mail.attempted)
        assertEquals(2, pendingOpDao.ops.size)
    }

    @Test
    fun `refresh replaces the inbox even when an op is rejected`() = runTest {
        val repository = newRepository(inboxDao, pendingOpDao, mail)
        seedRow(inboxDao, "stale")
        mail.failures["1"] = rejected()
        pendingOpDao.insert(PendingOp(uid = "1", type = PendingOp.ARCHIVE))
        mail.inbox = listOf(envelope("1"), envelope("new"))

        repository.refresh(TEST_ACCOUNT)

        assertEquals(setOf("new"), inboxDao.rows.keys)
        assertEquals(listOf("1" to PendingOp.ARCHIVE), pendingOpDao.queued())
    }

    @Test
    fun `refresh keeps queued actions applied when the flush loses the connection`() = runTest {
        val repository = newRepository(inboxDao, pendingOpDao, mail)
        seedRow(inboxDao, "stale")
        mail.failures["1"] = connectionLost()
        pendingOpDao.insert(PendingOp(uid = "1", type = PendingOp.DELETE))
        pendingOpDao.insert(PendingOp(uid = "2", type = PendingOp.SET_FLAGGED, value = true))
        pendingOpDao.insert(PendingOp(uid = "3", type = PendingOp.SET_SEEN, value = true))
        mail.inbox = listOf(envelope("1"), envelope("2"), envelope("3"), envelope("new"))

        repository.refresh(TEST_ACCOUNT)

        assertEquals(setOf("2", "3", "new"), inboxDao.rows.keys)
        assertEquals(true, inboxDao.rows.getValue("2").flagged)
        assertEquals(false, inboxDao.rows.getValue("3").unread)
        assertEquals(3, pendingOpDao.ops.size)
    }

    @Test
    fun `refresh throws when the inbox fetch fails`() = runTest {
        val repository = newRepository(inboxDao, pendingOpDao, mail)
        seedRow(inboxDao, "stale")
        val failure = connectionLost()
        mail.fetchFailure = failure

        val thrown = runCatching { repository.refresh(TEST_ACCOUNT) }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(setOf("stale"), inboxDao.rows.keys)
    }

    @Test
    fun `refresh leaves an archive inside its undo window visible`() = runTest {
        val repository = newRepository(inboxDao, pendingOpDao, mail)
        pendingOpDao.insert(
            PendingOp(uid = "1", type = PendingOp.ARCHIVE, notBeforeMs = currentTime + 5_000),
        )
        mail.inbox = listOf(envelope("1"))

        repository.refresh(TEST_ACCOUNT)

        assertEquals(setOf("1"), inboxDao.rows.keys)
        assertTrue(mail.attempted.isEmpty())
    }

    @Test
    fun `refresh doesn't fetch after the login is rejected`() = runTest {
        val repository = newRepository(inboxDao, pendingOpDao, mail)
        seedRow(inboxDao, "stale")
        val failure = AuthenticationFailedException("Authentication failed")
        mail.failures["1"] = failure
        pendingOpDao.insert(PendingOp(uid = "1", type = PendingOp.DELETE))
        mail.inbox = listOf(envelope("new"))

        val thrown = runCatching { repository.refresh(TEST_ACCOUNT) }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(0, mail.fetchCalls)
        assertEquals(setOf("stale"), inboxDao.rows.keys)
        assertEquals(listOf("1" to PendingOp.DELETE), pendingOpDao.queued())
    }
}
