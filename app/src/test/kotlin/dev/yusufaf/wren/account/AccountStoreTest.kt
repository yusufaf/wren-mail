package dev.yusufaf.wren.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fsck.k9.mail.ConnectionSecurity
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Passes reads through to [delegate] but can make every write fail, as a full disk would. */
private class FailingWritesDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    var failWrites = false

    override val data: Flow<Preferences> get() = delegate.data

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        if (failWrites) throw IOException("fake")
        return delegate.updateData(transform)
    }
}

/** XORs each byte with a constant and uses a fixed IV, so tests can predict and corrupt it. */
private class FakePasswordCipher : PasswordCipher {
    var encryptFailure: Exception? = null
    var decryptFailure: Exception? = null

    override fun encrypt(plaintext: String): EncryptedPassword {
        encryptFailure?.let { throw it }
        return EncryptedPassword(xor(plaintext.toByteArray(Charsets.UTF_8)), IV.copyOf())
    }

    override fun decrypt(encrypted: EncryptedPassword): String {
        decryptFailure?.let { throw it }
        if (!encrypted.iv.contentEquals(IV)) throw PasswordUnrecoverableException("fake")
        return String(xor(encrypted.ciphertext), Charsets.UTF_8)
    }

    private fun xor(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }

    private companion object {
        val IV = byteArrayOf(1, 2, 3)
    }
}

class AccountStoreTest {
    // Tests seed and inspect through dataStore; only AccountStore's own writes go through writes.
    private val dataStore = InMemoryDataStore()
    private val writes = FailingWritesDataStore(dataStore)
    private val cipher = FakePasswordCipher()
    private val store = AccountStore(writes, cipher)

    private suspend fun raw(): Preferences = dataStore.data.first()

    private fun Preferences.containsCanary() = asMap().values.any { it.toString().contains(CANARY) }

    /** Writes [TEST_ACCOUNT]'s plain-text fields, then lets [password] store the password. */
    private suspend fun seedAccount(password: (MutablePreferences) -> Unit) {
        dataStore.edit { prefs ->
            prefs[HOST] = TEST_ACCOUNT.host
            prefs[PORT] = TEST_ACCOUNT.port
            prefs[SECURITY] = TEST_ACCOUNT.security.name
            prefs[USERNAME] = TEST_ACCOUNT.username
            password(prefs)
        }
    }

    /** Writes the account the way v0.1.0 did, with the password in plain text. */
    private suspend fun seedLegacyAccount() = seedAccount { it[PASSWORD] = CANARY }

    @Test
    fun `save stores the password only as ciphertext`() = runTest {
        store.save(TEST_ACCOUNT)

        val prefs = raw()
        assertNull(prefs[PASSWORD])
        assertNotNull(prefs[PASSWORD_CIPHERTEXT])
        assertNotNull(prefs[PASSWORD_IV])
        assertFalse(prefs.containsCanary())
    }

    @Test
    fun `saved account reads back decrypted`() = runTest {
        store.save(TEST_ACCOUNT)

        assertEquals(TEST_ACCOUNT, store.account.first())
    }

    @Test
    fun `legacy plaintext password is encrypted on first read`() = runTest {
        seedLegacyAccount()

        assertEquals(TEST_ACCOUNT, store.account.first())

        val prefs = raw()
        assertNull(prefs[PASSWORD])
        val decoder = Base64.getDecoder()
        val encrypted = EncryptedPassword(
            decoder.decode(prefs[PASSWORD_CIPHERTEXT]),
            decoder.decode(prefs[PASSWORD_IV]),
        )
        assertEquals(CANARY, cipher.decrypt(encrypted))
        assertFalse(prefs.containsCanary())
    }

    @Test
    fun `legacy password stays readable when encryption fails`() = runTest {
        seedLegacyAccount()
        cipher.encryptFailure = GeneralSecurityException("fake")

        assertEquals(TEST_ACCOUNT, store.account.first())
        assertEquals(CANARY, raw()[PASSWORD])

        cipher.encryptFailure = null
        store.account.first()
        assertNull(raw()[PASSWORD])
    }

    @Test
    fun `legacy password stays readable when encryption throws unexpectedly`() = runTest {
        seedLegacyAccount()
        cipher.encryptFailure = IllegalStateException("fake")

        assertEquals(TEST_ACCOUNT, store.account.first())
        assertEquals(CANARY, raw()[PASSWORD])
    }

    @Test
    fun `transient decrypt failure keeps the account`() = runTest {
        store.save(TEST_ACCOUNT)
        val saved = raw()

        for (e in listOf(
            ProviderException("fake"),
            GeneralSecurityException("fake"),
            IllegalStateException("fake"),
            IOException("fake"),
        )) {
            cipher.decryptFailure = e
            assertNull(store.account.first())
            assertEquals(saved, raw())
        }

        cipher.decryptFailure = null
        assertEquals(TEST_ACCOUNT, store.account.first())
    }

    @Test
    fun `cancellation from decrypt is not swallowed`() = runTest {
        store.save(TEST_ACCOUNT)
        val saved = raw()
        cipher.decryptFailure = CancellationException("fake")

        val thrown = runCatching { store.account.first() }.exceptionOrNull()

        assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
        assertEquals(saved, raw())
    }

    @Test
    fun `cancellation from legacy encryption is not swallowed`() = runTest {
        seedLegacyAccount()
        cipher.encryptFailure = CancellationException("fake")

        val thrown = runCatching { store.account.first() }.exceptionOrNull()

        assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
        assertEquals(CANARY, raw()[PASSWORD])
    }

    @Test
    fun `legacy password stays readable when the migration write fails`() = runTest {
        seedLegacyAccount()
        writes.failWrites = true

        assertEquals(TEST_ACCOUNT, store.account.first())
        assertEquals(CANARY, raw()[PASSWORD])
    }

    @Test
    fun `save replaces a legacy plaintext password`() = runTest {
        seedLegacyAccount()

        store.save(TEST_ACCOUNT.copy(password = "new"))

        assertNull(raw()[PASSWORD])
        assertEquals("new", store.account.first()?.password)
    }

    @Test
    fun `undecryptable password clears the account`() = runTest {
        store.save(TEST_ACCOUNT)
        cipher.decryptFailure = PasswordUnrecoverableException("fake")

        assertNull(store.account.first())
        assertTrue(raw().asMap().isEmpty())
    }

    @Test
    fun `missing iv clears the account`() = runTest {
        seedAccount { it[PASSWORD_CIPHERTEXT] = "AQID" }

        assertNull(store.account.first())
        assertTrue(raw().asMap().isEmpty())
    }

    @Test
    fun `malformed ciphertext clears the account`() = runTest {
        seedAccount { prefs ->
            prefs[PASSWORD_CIPHERTEXT] = "not base64!"
            prefs[PASSWORD_IV] = "AQID"
        }

        assertNull(store.account.first())
        assertTrue(raw().asMap().isEmpty())
    }

    @Test
    fun `incomplete account is not cleared`() = runTest {
        dataStore.edit { it[HOST] = TEST_ACCOUNT.host }

        assertNull(store.account.first())
        assertEquals(TEST_ACCOUNT.host, raw()[HOST])
    }

    @Test
    fun `undecryptable password reads as no account when the clear write fails`() = runTest {
        store.save(TEST_ACCOUNT)
        cipher.decryptFailure = PasswordUnrecoverableException("fake")
        writes.failWrites = true

        assertNull(store.account.first())
        assertNotNull(raw()[PASSWORD_CIPHERTEXT])
    }
}
