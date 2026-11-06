@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.yusufaf.wren.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val UNDO_WINDOW_MS = 5_000L

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
