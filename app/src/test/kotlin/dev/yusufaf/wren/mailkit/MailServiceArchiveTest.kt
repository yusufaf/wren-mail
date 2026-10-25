package dev.yusufaf.wren.mailkit

import com.fsck.k9.mail.store.imap.FolderListItem
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.exception.MessagingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generous: it only has to exceed scheduling jitter, never real I/O. */
private const val MOVE_TIMEOUT_MS = 5_000L

class MailServiceArchiveTest {

    @Test
    fun `inbox open failure leaves the archive folder check intact`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)

        service.archiveMessage(TEST_ACCOUNT, "1")
        val store = factory.stores.single()
        assertEquals(1, store.script(ARCHIVE).existsCalls.get())

        store.script(INBOX).onOpen = { throw MessagingException("connection reset") }
        assertTrue(
            runCatching { service.archiveMessage(TEST_ACCOUNT, "2") }.exceptionOrNull()
                is MessagingException,
        )

        store.script(INBOX).onOpen = {}
        service.archiveMessage(TEST_ACCOUNT, "3")

        // Still one probe: opening the inbox says nothing about whether the
        // archive folder exists, so a failure there must not invalidate it.
        assertEquals(1, store.script(ARCHIVE).existsCalls.get())
    }

    @Test
    fun `move failure re-verifies the archive folder on the next attempt`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)

        service.archiveMessage(TEST_ACCOUNT, "1")
        val store = factory.stores.single()
        assertEquals(1, store.script(ARCHIVE).existsCalls.get())

        store.script(INBOX).onMove = {
            throw MessagingException("NO [TRYCREATE] no such mailbox")
        }
        assertTrue(
            runCatching { service.archiveMessage(TEST_ACCOUNT, "2") }.exceptionOrNull()
                is MessagingException,
        )

        store.script(INBOX).onMove = {}
        service.archiveMessage(TEST_ACCOUNT, "3")

        // The move is the one call that targets the archive folder, so its
        // failure is the one that must invalidate the cached check.
        assertEquals(2, store.script(ARCHIVE).existsCalls.get())
    }

    @Test
    fun `archive folder probe failure leaves the folder unverified`() = runBlocking {
        val factory = RecordingStoreFactory { store ->
            store.script(ARCHIVE).folderExists = false
            store.script(ARCHIVE).onCreate = { throw MessagingException("permission denied") }
        }
        val service = MailService(FakeSocketFactory, factory)

        assertTrue(
            runCatching { service.archiveMessage(TEST_ACCOUNT, "1") }.exceptionOrNull()
                is MessagingException,
        )

        val store = factory.stores.single()
        store.script(ARCHIVE).onCreate = {}
        service.archiveMessage(TEST_ACCOUNT, "2")

        // Probed and created again, because the first attempt never finished
        // confirming the folder — there was no successful check to cache.
        assertEquals(2, store.script(ARCHIVE).existsCalls.get())
        assertEquals(2, store.script(ARCHIVE).createCalls.get())
    }

    @Test
    fun `inbox is closed when the move fails`() = runBlocking {
        val factory = RecordingStoreFactory { store ->
            store.script(INBOX).onMove = { throw MessagingException("boom") }
        }
        val service = MailService(FakeSocketFactory, factory)

        assertTrue(
            runCatching { service.archiveMessage(TEST_ACCOUNT, "1") }.exceptionOrNull()
                is MessagingException,
        )

        assertEquals(1, factory.stores.single().script(INBOX).closeCalls.get())
    }

    @Test
    fun `move failure on a replaced store keeps the new store verified`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)

        service.archiveMessage(TEST_ACCOUNT, "1")
        val oldStore = factory.stores.single()

        val moveEntered = CountDownLatch(1)
        val moveMayFail = CountDownLatch(1)
        oldStore.script(INBOX).onMove = {
            moveEntered.countDown()
            check(moveMayFail.await(MOVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "move was never released" }
            throw MessagingException("connection reset")
        }
        val inFlight = async(Dispatchers.Default) {
            runCatching { service.archiveMessage(TEST_ACCOUNT, "2") }.exceptionOrNull()
        }
        assertTrue(moveEntered.await(MOVE_TIMEOUT_MS, TimeUnit.MILLISECONDS))

        // The account changes while that archive is still in flight; the
        // replacement store verifies its own archive folder.
        service.archiveMessage(OTHER_ACCOUNT, "3")
        val newStore = factory.stores[1]
        assertEquals(1, newStore.script(ARCHIVE).existsCalls.get())

        moveMayFail.countDown()
        assertTrue(inFlight.await() is MessagingException)

        // The old store's failure says nothing about the new store's folder.
        service.archiveMessage(OTHER_ACCOUNT, "4")
        assertEquals(1, newStore.script(ARCHIVE).existsCalls.get())
    }

    @Test
    fun `archives to the folder the server marks Archive`() = runBlocking {
        val moved = CopyOnWriteArrayList<String>()
        val factory = recordingMoves(moved, MARKED_ARCHIVE)
        val service = MailService(FakeSocketFactory, factory)

        service.archiveMessage(TEST_ACCOUNT, "1")

        assertEquals(listOf("Archiv"), moved)
        val store = factory.stores.single()
        assertEquals(0, store.script(ARCHIVE).existsCalls.get())
        assertEquals(0, store.script(ARCHIVE).createCalls.get())
    }

    @Test
    fun `archives to the All folder when nothing is marked Archive`() = runBlocking {
        val moved = CopyOnWriteArrayList<String>()
        val factory = recordingMoves(
            moved,
            listOf(listed("[Gmail]/Sent Mail", "\\Sent"), listed(ALL_MAIL, "\\HasNoChildren", "\\All")),
        )
        val service = MailService(FakeSocketFactory, factory)

        service.archiveMessage(TEST_ACCOUNT, "1")

        assertEquals(listOf(ALL_MAIL), moved)
        val store = factory.stores.single()
        assertEquals(0, store.script(ARCHIVE).existsCalls.get())
        assertEquals(0, store.script(ARCHIVE).createCalls.get())
    }

    @Test
    fun `prefers the Archive folder over the All folder`() = runBlocking {
        val moved = CopyOnWriteArrayList<String>()
        // \All listed first, so list order can't explain the choice.
        val factory = recordingMoves(moved, listOf(listed(ALL_MAIL, "\\All"), listed("Archive.2026", "\\Archive")))
        val service = MailService(FakeSocketFactory, factory)

        service.archiveMessage(TEST_ACCOUNT, "1")

        assertEquals(listOf("Archive.2026"), moved)
    }

    @Test
    fun `falls back to creating Archive when no folder is marked`() = runBlocking {
        val moved = CopyOnWriteArrayList<String>()
        val factory = recordingMoves(moved, listOf(listed("Sent", "\\Sent"))) { store ->
            store.script(ARCHIVE).folderExists = false
        }
        val service = MailService(FakeSocketFactory, factory)

        service.archiveMessage(TEST_ACCOUNT, "1")

        assertEquals(listOf(ARCHIVE), moved)
        val store = factory.stores.single()
        assertEquals(1, store.script(ARCHIVE).existsCalls.get())
        assertEquals(1, store.script(ARCHIVE).createCalls.get())
    }

    @Test
    fun `lists folders once per store`() = runBlocking {
        val factory = recordingMoves(CopyOnWriteArrayList(), MARKED_ARCHIVE)
        val service = MailService(FakeSocketFactory, factory)

        service.archiveMessage(TEST_ACCOUNT, "1")
        service.archiveMessage(TEST_ACCOUNT, "2")

        assertEquals(1, factory.stores.single().getFoldersCalls.get())
    }

    @Test
    fun `move failure re-resolves the archive folder`() = runBlocking {
        val moved = CopyOnWriteArrayList<String>()
        val factory = recordingMoves(moved, MARKED_ARCHIVE)
        val service = MailService(FakeSocketFactory, factory)

        service.archiveMessage(TEST_ACCOUNT, "1")
        val store = factory.stores.single()
        val recordMove = store.script(INBOX).onMove

        store.script(INBOX).onMove = { throw MessagingException("NO [TRYCREATE] no such mailbox") }
        assertTrue(
            runCatching { service.archiveMessage(TEST_ACCOUNT, "2") }.exceptionOrNull()
                is MessagingException,
        )

        store.script(INBOX).onMove = recordMove
        service.archiveMessage(TEST_ACCOUNT, "3")

        // The folder may have been renamed or removed, so the move failure
        // must send the next archive back to the server's folder list.
        assertEquals(2, store.getFoldersCalls.get())
        assertEquals(listOf("Archiv", "Archiv"), moved)
    }

    @Test
    fun `folder list failure neither creates Archive nor caches`() = runBlocking {
        val moved = CopyOnWriteArrayList<String>()
        val factory = recordingMoves(moved, emptyList()) { store ->
            store.onGetFolders = { throw MessagingException("BAD") }
        }
        val service = MailService(FakeSocketFactory, factory)

        assertTrue(
            runCatching { service.archiveMessage(TEST_ACCOUNT, "1") }.exceptionOrNull()
                is MessagingException,
        )

        val store = factory.stores.single()
        // Falling back to Archive on a failed LIST would create the very
        // stray folder that discovery exists to avoid.
        assertEquals(0, store.script(INBOX).moveCalls.get())
        assertEquals(0, store.script(ARCHIVE).existsCalls.get())
        assertEquals(0, store.script(ARCHIVE).createCalls.get())

        store.onGetFolders = {}
        store.listedFolders = MARKED_ARCHIVE
        service.archiveMessage(TEST_ACCOUNT, "2")

        assertEquals(2, store.getFoldersCalls.get())
        assertEquals(listOf("Archiv"), moved)
    }

    private fun recordingMoves(
        moved: MutableList<String>,
        folders: List<FolderListItem>,
        configure: (FakeImapStore) -> Unit = {},
    ) = RecordingStoreFactory { store ->
        store.listedFolders = folders
        store.script(INBOX).onMove = { moved += it.serverId }
        configure(store)
    }

    private companion object {
        val MARKED_ARCHIVE = listOf(listed("Archiv", "\\HasNoChildren", "\\Archive"), listed("Sent", "\\Sent"))
    }
}
