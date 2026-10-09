@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package dev.yusufaf.wren.mailkit

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generous: it only has to exceed scheduling jitter, never real I/O. */
private const val RENDEZVOUS_TIMEOUT_MS = 5_000L

/** Long enough that an unserialized second open lands inside it. */
private const val COLD_OVERLAP_WINDOW_MS = 200L

class MailServiceStoreTest {

    @Test
    fun `reuses one store across operations on the same account`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)

        service.setFlagged(TEST_ACCOUNT, "1", flagged = true)
        service.setFlagged(TEST_ACCOUNT, "2", flagged = true)

        assertEquals(1, factory.createCalls)
        assertEquals(2, factory.stores.single().script(INBOX).setFlagsCalls.get())
    }

    @Test
    fun `two operations overlap instead of serializing on a warm store`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)
        // Warm the store with a first operation before arming the rendezvous.
        service.setFlagged(TEST_ACCOUNT, "0", flagged = true)

        // Both operations park here until the other arrives. Under a mutex
        // that spans the whole IMAP round trip the first operation never lets
        // the second one in, so the await times out and the test fails.
        val rendezvous = CyclicBarrier(2)
        factory.stores.single().script(INBOX).onOpen = {
            rendezvous.await(RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        val refresh = async(Dispatchers.Default) { service.fetchInbox(TEST_ACCOUNT) }
        val flag = async(Dispatchers.Default) { service.setFlagged(TEST_ACCOUNT, "1", flagged = true) }

        assertEquals(emptyList<Envelope>(), refresh.await())
        flag.await()
    }

    @Test
    fun `a cold store is warmed with a single probe before operations overlap`() = runBlocking {
        // RealImapStore writes its path-prefix state without synchronization
        // while the first connection opens, so the warm-up probe must finish
        // before any operation opens a folder. onExists holds the probe open
        // long enough for an unserialized open to land inside it.
        val warming = AtomicBoolean()
        val violations = AtomicInteger()
        val factory = RecordingStoreFactory { store ->
            store.script(INBOX).onExists = {
                warming.set(true)
                Thread.sleep(COLD_OVERLAP_WINDOW_MS)
                warming.set(false)
            }
            store.script(INBOX).onOpen = {
                if (warming.get()) violations.incrementAndGet()
            }
        }
        val service = MailService(FakeSocketFactory, factory)

        val first = async(Dispatchers.Default) { service.setFlagged(TEST_ACCOUNT, "1", flagged = true) }
        val second = async(Dispatchers.Default) { service.setFlagged(TEST_ACCOUNT, "2", flagged = true) }
        first.await()
        second.await()

        assertEquals(0, violations.get())
        assertEquals(1, factory.stores.single().script(INBOX).existsCalls.get())
        assertEquals(1, factory.createCalls)
    }

    @Test
    fun `a rebuilt store is warmed again`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)

        service.setFlagged(TEST_ACCOUNT, "1", flagged = true)
        service.setFlagged(OTHER_ACCOUNT, "2", flagged = true)

        assertEquals(2, factory.createCalls)
        assertEquals(1, factory.stores[0].script(INBOX).existsCalls.get())
        assertEquals(1, factory.stores[1].script(INBOX).existsCalls.get())
    }

    @Test
    fun `a failed warm-up leaves the store cold`() = runBlocking {
        val failWarmUp = AtomicBoolean(true)
        val factory = RecordingStoreFactory { store ->
            store.script(INBOX).onExists = {
                if (failWarmUp.get()) throw IllegalStateException("probe failed")
            }
        }
        val service = MailService(FakeSocketFactory, factory)

        val failure = runCatching { service.setFlagged(TEST_ACCOUNT, "1", flagged = true) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)

        failWarmUp.set(false)
        service.setFlagged(TEST_ACCOUNT, "2", flagged = true)

        val inbox = factory.stores.single().script(INBOX)
        assertEquals(2, inbox.existsCalls.get())
        assertEquals(1, inbox.setFlagsCalls.get())
    }

    @Test
    fun `releasing connections mid-operation does not break the operation`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)
        service.setFlagged(TEST_ACCOUNT, "0", flagged = true)

        val operationEntered = CountDownLatch(1)
        val connectionsReleased = CountDownLatch(1)
        factory.stores.single().script(INBOX).onOpen = {
            operationEntered.countDown()
            // CountDownLatch.await(timeout) returns false on timeout
            // rather than throwing, so check it: under a lock spanning
            // the round trip, releaseConnections() can never run while
            // this operation is parked, and this is what turns that into
            // a failure instead of a slow pass.
            check(connectionsReleased.await(RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "releaseConnections() was blocked behind the in-flight operation"
            }
        }

        val flag = async(Dispatchers.Default) { service.setFlagged(TEST_ACCOUNT, "1", flagged = true) }
        assertTrue(operationEntered.await(RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS))

        // The app backgrounding while a sync is in flight.
        service.releaseConnections()
        connectionsReleased.countDown()
        flag.await()

        val store = factory.stores.single()
        assertEquals(1, store.closeAllConnectionsCalls.get())
        // The warm-up flag plus the interrupted one.
        assertEquals(2, store.script(INBOX).setFlagsCalls.get())
    }

    @Test
    fun `resetting connections closes the pool and rebuilds the store on next use`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)
        service.setFlagged(TEST_ACCOUNT, "0", flagged = true)

        service.resetConnections()
        service.setFlagged(TEST_ACCOUNT, "1", flagged = true)

        assertEquals(2, factory.createCalls)
        assertEquals(1, factory.stores[0].closeAllConnectionsCalls.get())
        // The rebuilt store is cold, so it gets its own warm-up probe.
        assertEquals(1, factory.stores[1].script(INBOX).existsCalls.get())
    }

    @Test
    fun `resetting before any operation is a no-op`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)

        service.resetConnections()

        assertEquals(0, factory.createCalls)
    }

    @Test
    fun `an operation in flight during a reset still completes`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)
        service.setFlagged(TEST_ACCOUNT, "0", flagged = true)

        val operationEntered = CountDownLatch(1)
        val connectionsReset = CountDownLatch(1)
        factory.stores.single().script(INBOX).onOpen = {
            operationEntered.countDown()
            check(connectionsReset.await(RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "resetConnections() was blocked behind the in-flight operation"
            }
        }

        val flag = async(Dispatchers.Default) { service.setFlagged(TEST_ACCOUNT, "1", flagged = true) }
        assertTrue(operationEntered.await(RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS))

        service.resetConnections()
        connectionsReset.countDown()
        flag.await()

        assertEquals(1, factory.stores.single().closeAllConnectionsCalls.get())
        service.setFlagged(TEST_ACCOUNT, "2", flagged = true)
        assertEquals(2, factory.createCalls)
    }

    @Test
    fun `changing the account rebuilds the store and re-verifies the archive folder`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)

        service.archiveMessage(TEST_ACCOUNT, "1")
        service.archiveMessage(OTHER_ACCOUNT, "2")

        assertEquals(2, factory.createCalls)
        assertEquals(1, factory.stores[0].closeAllConnectionsCalls.get())
        assertEquals(1, factory.stores[0].script(ARCHIVE).existsCalls.get())
        // The new store has its own archive folder to confirm.
        assertEquals(1, factory.stores[1].script(ARCHIVE).existsCalls.get())
    }

    @Test
    fun `archive folder stays verified across operations on different threads`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)

        // Distinct dispatchers so the second archive almost certainly runs on
        // a different thread than the first. A test cannot force a JMM
        // visibility failure, so this pins the observable behavior @Volatile
        // exists to protect: one probe per store lifetime, whoever asks.
        withContext(Dispatchers.Default) { service.archiveMessage(TEST_ACCOUNT, "1") }
        newSingleThreadContext("archive-2").use { context ->
            withContext(context) { service.archiveMessage(TEST_ACCOUNT, "2") }
        }

        assertEquals(1, factory.stores.single().script(ARCHIVE).existsCalls.get())
    }
}
