package dev.yusufaf.wren.mailkit

import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.exception.MessagingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
