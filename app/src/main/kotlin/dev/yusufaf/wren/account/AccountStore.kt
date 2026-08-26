package dev.yusufaf.wren.account

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fsck.k9.mail.ConnectionSecurity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.accountDataStore by preferencesDataStore(name = "account")

/**
 * Persists the single v1 account in app-private DataStore. The password is
 * stored in plain text inside the app sandbox; watch-local at-rest encryption
 * is a later hardening step.
 */
class AccountStore(private val context: Context) {

    private object Keys {
        val host = stringPreferencesKey("host")
        val port = intPreferencesKey("port")
        val security = stringPreferencesKey("security")
        val username = stringPreferencesKey("username")
        val password = stringPreferencesKey("password")
    }

    val account: Flow<Account?> = context.accountDataStore.data.map { prefs ->
        val host = prefs[Keys.host] ?: return@map null
        Account(
            host = host,
            port = prefs[Keys.port] ?: return@map null,
            security = prefs[Keys.security]
                ?.let { runCatching { ConnectionSecurity.valueOf(it) }.getOrNull() }
                ?: return@map null,
            username = prefs[Keys.username] ?: return@map null,
            password = prefs[Keys.password] ?: return@map null,
        )
    }

    suspend fun save(account: Account) {
        context.accountDataStore.edit { prefs ->
            prefs[Keys.host] = account.host
            prefs[Keys.port] = account.port
            prefs[Keys.security] = account.security.name
            prefs[Keys.username] = account.username
            prefs[Keys.password] = account.password
        }
    }

    suspend fun clear() {
        context.accountDataStore.edit { it.clear() }
    }
}
