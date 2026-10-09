package dev.yusufaf.wren.mailkit

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Generous: it only has to exceed scheduling jitter, never real I/O. */
private const val RENDEZVOUS_TIMEOUT_MS = 5_000L

class TrustExceptionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val certificate = TlsFixture.certificate

    private fun trustExceptions(
        directory: File = tmp.newFolder("ssl"),
        configure: (FakeImapStore) -> Unit = {},
    ): Setup {
        val socketFactory = WrenTrustedSocketFactory(directory)
        val storeFactory = RecordingStoreFactory(configure)
        val service = MailService(FakeSocketFactory, storeFactory)
        return Setup(socketFactory, storeFactory, service, TrustExceptions(socketFactory, service))
    }

    private class Setup(
        val socketFactory: WrenTrustedSocketFactory,
        val storeFactory: RecordingStoreFactory,
        val service: MailService,
        val trustExceptions: TrustExceptions,
    )

    @Test
    fun `accepted exceptions are listed sorted by host then port`() = runBlocking {
        val setup = trustExceptions()
        setup.trustExceptions.accept("mail.example", 993, certificate)
        setup.trustExceptions.accept("localhost", 995, certificate)
        setup.trustExceptions.accept("localhost", 993, certificate)

        val listed = setup.trustExceptions.list().map { it.host to it.port }

        assertEquals(listOf("localhost" to 993, "localhost" to 995, "mail.example" to 993), listed)
    }

    @Test
    fun `revoke removes the exception before resetting connections`() = runBlocking {
        val seenAtReset = CopyOnWriteArrayList<List<TrustException>>()
        lateinit var setup: Setup
        setup = trustExceptions(configure = { store ->
            store.onCloseAllConnections = { seenAtReset += setup.socketFactory.trustExceptions() }
        })
        setup.trustExceptions.accept("localhost", 993, certificate)
        setup.service.setFlagged(TEST_ACCOUNT, "1", flagged = true)

        setup.trustExceptions.revoke("localhost", 993)

        assertEquals(listOf(emptyList<TrustException>()), seenAtReset.toList())
        assertEquals(1, setup.storeFactory.stores[0].closeAllConnectionsCalls.get())
        setup.service.setFlagged(TEST_ACCOUNT, "2", flagged = true)
        assertEquals(2, setup.storeFactory.createCalls)
    }

    @Test
    fun `revoke with no cached store only removes the exception`() = runBlocking {
        val setup = trustExceptions()
        setup.trustExceptions.accept("localhost", 993, certificate)

        setup.trustExceptions.revoke("localhost", 993)

        assertTrue(setup.trustExceptions.list().isEmpty())
        assertEquals(0, setup.storeFactory.createCalls)
    }

    @Test
    fun `revoke still resets connections when persisting the removal fails`() = runBlocking {
        val directory = tmp.newFolder("ssl")
        val setup = trustExceptions(directory)
        setup.trustExceptions.accept("localhost", 993, certificate)
        setup.service.setFlagged(TEST_ACCOUNT, "1", flagged = true)
        // A FileOutputStream on a directory throws FileNotFoundException, which
        // LocalKeyStore.deleteCertificate lets escape after the in-memory delete.
        val keyStoreFile = directory.listFiles()!!.single()
        check(keyStoreFile.delete())
        check(keyStoreFile.mkdir())

        val failure = runCatching { setup.trustExceptions.revoke("localhost", 993) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(1, setup.storeFactory.stores[0].closeAllConnectionsCalls.get())
        assertTrue(setup.trustExceptions.list().isEmpty())
    }

    @Test
    fun `a revoke cancelled before it starts still removes the exception and resets connections`() = runBlocking {
        val setup = trustExceptions()
        setup.trustExceptions.accept("localhost", 993, certificate)
        setup.service.setFlagged(TEST_ACCOUNT, "1", flagged = true)

        // The screen's scope is already cancelled when revoke is entered, e.g.
        // the user swiped back right after tapping Revoke.
        launch(Dispatchers.Default) {
            currentCoroutineContext().job.cancel()
            setup.trustExceptions.revoke("localhost", 993)
        }.join()

        assertTrue(setup.socketFactory.trustExceptions().isEmpty())
        assertEquals(1, setup.storeFactory.stores[0].closeAllConnectionsCalls.get())
    }

    @Test
    fun `a cancelled revoke still resets connections`() = runBlocking {
        val warmUpEntered = CountDownLatch(1)
        val releaseWarmUp = CountDownLatch(1)
        val setup = trustExceptions(configure = { store ->
            store.script(INBOX).onExists = {
                warmUpEntered.countDown()
                check(releaseWarmUp.await(RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    "the warm-up was never released"
                }
            }
        })
        setup.trustExceptions.accept("localhost", 993, certificate)

        // The cold store is cached and its warm-up holds the store mutex, so
        // the reset has to wait behind it.
        val operation = async(Dispatchers.Default) {
            setup.service.setFlagged(TEST_ACCOUNT, "1", flagged = true)
        }
        assertTrue(warmUpEntered.await(RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val revoke = launch(Dispatchers.Default) { setup.trustExceptions.revoke("localhost", 993) }
        // Cancel only once the removal is visible, so the cancellation lands on
        // the reset whichever suspension point it was parked at.
        var polls = 0
        while (setup.socketFactory.trustExceptions().isNotEmpty()) {
            if (++polls > 50) fail("the exception was never removed")
            delay(20)
        }

        revoke.cancel()
        releaseWarmUp.countDown()
        revoke.join()
        operation.await()

        assertEquals(1, setup.storeFactory.stores[0].closeAllConnectionsCalls.get())
        setup.service.setFlagged(TEST_ACCOUNT, "2", flagged = true)
        assertEquals(2, setup.storeFactory.createCalls)
    }
}
