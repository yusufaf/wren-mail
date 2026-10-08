package dev.yusufaf.wren.mailkit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MailServiceStoreTest {

    @Test
    fun `reuses one store across operations on the same account`() = runBlocking {
        val factory = RecordingStoreFactory()
        val service = MailService(FakeSocketFactory, factory)

        service.setFlagged(TEST_ACCOUNT, "1", flagged = true)
        service.setFlagged(TEST_ACCOUNT, "2", flagged = true)

        assertEquals(1, factory.createCalls)
    }
}
