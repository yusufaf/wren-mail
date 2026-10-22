package dev.yusufaf.wren.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fsck.k9.mail.ConnectionSecurity
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CANARY = "wren-canary-7f3"

private val TEST_ACCOUNT = Account(
    host = "imap.example.com",
    port = 993,
    security = ConnectionSecurity.SSL_TLS_REQUIRED,
    username = "wren",
    password = CANARY,
)

// The on-disk key names, pinned here so a rename in AccountStore breaks the tests.
private val HOST = stringPreferencesKey("host")
private val PORT = intPreferencesKey("port")
private val SECURITY = stringPreferencesKey("security")
private val USERNAME = stringPreferencesKey("username")
private val PASSWORD = stringPreferencesKey("password")
private val PASSWORD_CIPHERTEXT = stringPreferencesKey("password_ciphertext")
private val PASSWORD_IV = stringPreferencesKey("password_iv")

/** In-memory `DataStore<Preferences>`: writes are serialized like the real one and re-emit `data`. */
private class InMemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> get() = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}

/** XORs each byte with a constant and uses a fixed IV, so tests can predict and corrupt it. */
private class FakePasswordCipher : PasswordCipher {
    var failEncrypt = false
    var failDecrypt = false

    override fun encrypt(plaintext: String): EncryptedPassword {
        if (failEncrypt) throw GeneralSecurityException("fake")
        return EncryptedPassword(xor(plaintext.toByteArray(Charsets.UTF_8)), IV.copyOf())
    }

    override fun decrypt(encrypted: EncryptedPassword): String {
        if (failDecrypt || !encrypted.iv.contentEquals(IV)) throw AEADBadTagException("fake")
        return String(xor(encrypted.ciphertext), Charsets.UTF_8)
    }

    private fun xor(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }

    private companion object {
        val IV = byteArrayOf(1, 2, 3)
    }
}

class AccountStoreTest {
    private val dataStore = InMemoryDataStore()
    private val cipher = FakePasswordCipher()
    private val store = AccountStore(dataStore, cipher)

    private suspend fun raw(): Preferences = dataStore.data.first()

    private fun Preferences.containsCanary() = asMap().values.any { it.toString().contains(CANARY) }

    @Test
    fun `save stores the password only as ciphertext`() = runTest {
        store.save(TEST_ACCOUNT)

        val prefs = raw()
        assertNull(prefs[PASSWORD])
        assertNotNull(prefs[PASSWORD_CIPHERTEXT])
        assertNotNull(prefs[PASSWORD_IV])
        assertTrue(!prefs.containsCanary())
    }

    @Test
    fun `saved account reads back decrypted`() = runTest {
        store.save(TEST_ACCOUNT)

        assertEquals(TEST_ACCOUNT, store.account.first())
    }
}
