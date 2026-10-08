@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package dev.yusufaf.wren.mailkit

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
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
    }

    @Test
    fun `two operations overlap instead of serializing on a warm store`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)
        // The first operation on a store runs alone (see the cold-store test);
        // warm it up before arming the rendezvous.
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

        // Every operation shared one store: the lock around the lookup is what
        // stops a concurrent first call from building two.
        assertEquals(1, factory.createCalls)
    }

    /** Review Focus 6. */
    @Test
    fun `the first operation on a cold store runs alone`() = runBlocking {
        // RealImapStore writes its path-prefix state without synchronization
        // while the first connection opens, so an overlapping second open can
        // cache a stale combinedPrefix. onOpen reports how many opens are in
        // flight; the sleep keeps each one in flight long enough to overlap.
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val factory = RecordingStoreFactory { store ->
            store.script(INBOX).onOpen = {
                peak.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
                Thread.sleep(COLD_OVERLAP_WINDOW_MS)
                inFlight.decrementAndGet()
            }
        }
        val service = MailService(FakeSocketFactory, factory)

        val first = async(Dispatchers.Default) { service.setFlagged(TEST_ACCOUNT, "1", flagged = true) }
        val second = async(Dispatchers.Default) { service.setFlagged(TEST_ACCOUNT, "2", flagged = true) }
        first.await()
        second.await()

        assertEquals(1, peak.get())
        assertEquals(1, factory.createCalls)
    }

    /** Review Focus 3. */
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

    /** Review Focus 4. */
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

    /** Review Focus 5. */
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
