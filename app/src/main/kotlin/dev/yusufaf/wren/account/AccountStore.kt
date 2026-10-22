package dev.yusufaf.wren.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fsck.k9.mail.ConnectionSecurity
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.thunderbird.legacy.logging.Log

private val Context.accountDataStore by preferencesDataStore(name = "account")

private val base64Encoder = Base64.getEncoder()
private val base64Decoder = Base64.getDecoder()

/**
 * Persists the single v1 account in app-private DataStore. The password is
 * encrypted with an Android Keystore key and only its ciphertext and IV are
 * stored; host, port, security and username stay in plain text. A v0.1.0
 * plaintext password is encrypted on first read, and a password that can't be
 * decrypted clears the account so setup shows again.
 */
class AccountStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: PasswordCipher,
) {
    constructor(context: Context) : this(context.accountDataStore, KeystorePasswordCipher())

    private object Keys {
        val host = stringPreferencesKey("host")
        val port = intPreferencesKey("port")
        val security = stringPreferencesKey("security")
        val username = stringPreferencesKey("username")
        val passwordCiphertext = stringPreferencesKey("password_ciphertext")
        val passwordIv = stringPreferencesKey("password_iv")

        // v0.1.0 kept the password here in plain text; read only to migrate it.
        val legacyPassword = stringPreferencesKey("password")
    }

    val account: Flow<Account?> = dataStore.data
        .map { prefs -> read(prefs) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    suspend fun save(account: Account) {
        val encrypted = withContext(Dispatchers.IO) { cipher.encrypt(account.password) }
        dataStore.edit { prefs ->
            prefs[Keys.host] = account.host
            prefs[Keys.port] = account.port
            prefs[Keys.security] = account.security.name
            prefs[Keys.username] = account.username
            prefs.putPassword(encrypted)
            prefs.remove(Keys.legacyPassword)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private suspend fun read(prefs: Preferences): Account? {
        val host = prefs[Keys.host] ?: return null
        val port = prefs[Keys.port] ?: return null
        val security = prefs[Keys.security]
            ?.let { runCatching { ConnectionSecurity.valueOf(it) }.getOrNull() }
            ?: return null
        val username = prefs[Keys.username] ?: return null
        val password = prefs[Keys.legacyPassword]?.also { migrateLegacyPassword(it) }
            ?: decryptPassword(prefs)
            ?: return null
        return Account(host, port, security, username, password)
    }

    /**
     * Replaces a v0.1.0 plaintext password with its ciphertext. On failure the
     * plaintext stays, so the user stays signed in and the next read retries.
     */
    private suspend fun migrateLegacyPassword(plaintext: String) {
        val encrypted = try {
            cipher.encrypt(plaintext)
        } catch (e: GeneralSecurityException) {
            Log.w(e, "Couldn't encrypt the legacy password; will retry")
            return
        } catch (e: ProviderException) {
            Log.w(e, "Couldn't encrypt the legacy password; will retry")
            return
        }
        try {
            dataStore.edit { prefs ->
                // Only the first writer migrates: another collector or a save may have got here first.
                if (prefs[Keys.legacyPassword] == plaintext) {
                    prefs.putPassword(encrypted)
                    prefs.remove(Keys.legacyPassword)
                }
            }
        } catch (e: IOException) {
            Log.w(e, "Couldn't write the encrypted legacy password; will retry")
        }
    }

    /**
     * Returns null, and clears the account so setup shows again, when the
     * password can't be decrypted: the Keystore key is gone or the stored data
     * is damaged. Retrying would fail the same way.
     */
    private suspend fun decryptPassword(prefs: Preferences): String? {
        val ciphertext = prefs[Keys.passwordCiphertext] ?: return null
        val password = try {
            val iv = prefs[Keys.passwordIv] ?: throw GeneralSecurityException("No IV stored")
            cipher.decrypt(EncryptedPassword(base64Decoder.decode(ciphertext), base64Decoder.decode(iv)))
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: ProviderException) {
            null
        } catch (e: IllegalArgumentException) {
            null // Not valid Base64.
        }
        if (password == null) {
            Log.w("Stored password can't be decrypted; clearing the account")
            try {
                // Skip the clear if a save replaced the password since this read.
                dataStore.edit { p -> if (p[Keys.passwordCiphertext] == ciphertext) p.clear() }
            } catch (e: IOException) {
                Log.w(e, "Couldn't clear the account; will retry")
            }
        }
        return password
    }

    private fun MutablePreferences.putPassword(encrypted: EncryptedPassword) {
        this[Keys.passwordCiphertext] = base64Encoder.encodeToString(encrypted.ciphertext)
        this[Keys.passwordIv] = base64Encoder.encodeToString(encrypted.iv)
    }
}
