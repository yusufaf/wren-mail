package dev.yusufaf.wren

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation3.SwipeDismissableSceneStrategy
import com.fsck.k9.mail.ConnectionSecurity
import dev.yusufaf.wren.account.Account
import dev.yusufaf.wren.account.AccountStore
import dev.yusufaf.wren.data.MailRepository
import dev.yusufaf.wren.ui.AccountSetupScreen
import dev.yusufaf.wren.ui.InboxScreen
import dev.yusufaf.wren.ui.InboxState
import dev.yusufaf.wren.ui.MessageScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WrenApplication
        seedAccountFromIntentForDebug(app.accountStore, intent)
        setContent {
            MaterialTheme {
                WrenApp(app.accountStore, app.repository)
            }
        }
    }
}

/**
 * Debuggable builds only: seed the account from launch intent extras so
 * emulator testing doesn't require the on-watch keyboard, e.g.
 * `adb shell am start -n dev.yusufaf.wren/.MainActivity --es wren.host 10.0.2.2
 *  --ei wren.port 3143 --es wren.security NONE --es wren.username wren
 *  --es wren.password secret`
 */
private fun ComponentActivity.seedAccountFromIntentForDebug(accountStore: AccountStore, intent: Intent?) {
    val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (!debuggable) return
    val host = intent?.getStringExtra("wren.host") ?: return
    val security = intent.getStringExtra("wren.security")
        ?.let { runCatching { ConnectionSecurity.valueOf(it) }.getOrNull() }
        ?: ConnectionSecurity.SSL_TLS_REQUIRED
    val account = Account(
        host = host,
        port = intent.getIntExtra("wren.port", Account.defaultPortFor(security)),
        security = security,
        username = intent.getStringExtra("wren.username") ?: "",
        password = intent.getStringExtra("wren.password") ?: "",
    )
    if (account.isComplete) {
        // Blocking is fine here: debug-only, one small preferences write before
        // first composition, avoiding a race with the start-screen decision.
        runBlocking { accountStore.save(account) }
    }
}

@Serializable
private data object InboxKey : NavKey

@Serializable
private data object SetupKey : NavKey

@Serializable
private data class MessageKey(val uid: String) : NavKey

@Composable
fun WrenApp(accountStore: AccountStore, repository: MailRepository) {
    val backStack = rememberNavBackStack(InboxKey)
    var account by remember { mutableStateOf<Account?>(null) }
    var accountLoaded by remember { mutableStateOf(false) }
    val envelopes by repository.inbox.collectAsState(initial = null)
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        accountStore.account.collect { stored ->
            account = stored
            if (!accountLoaded) {
                accountLoaded = true
                if (stored == null) backStack.add(SetupKey)
            }
        }
    }

    fun refreshInbox() {
        val current = account ?: return
        if (refreshing) return
        refreshing = true
        refreshError = null
        scope.launch {
            try {
                repository.refresh(current)
            } catch (e: Exception) {
                refreshError = e.message ?: e.toString()
            }
            refreshing = false
        }
    }

    // Covers first load (account arriving) and every return to the inbox —
    // action-driven pops and swipe-dismiss alike. The cached list shows
    // instantly either way; this only kicks off the network update.
    LaunchedEffect(account, backStack.lastOrNull()) {
        if (account != null && backStack.lastOrNull() is InboxKey) refreshInbox()
    }

    AppScaffold(timeText = { TimeText() }) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            sceneStrategy = SwipeDismissableSceneStrategy(),
            entryProvider = entryProvider {
                entry<InboxKey> {
                    InboxScreen(
                        state = InboxState(envelopes, refreshing, refreshError),
                        onRefresh = ::refreshInbox,
                        onOpenSettings = { backStack.add(SetupKey) },
                        onOpenMessage = { uid -> backStack.add(MessageKey(uid)) },
                    )
                }
                entry<SetupKey> {
                    AccountSetupScreen(
                        initial = account,
                        onValidateAndSave = { candidate ->
                            try {
                                repository.checkSettings(candidate)
                                accountStore.save(candidate)
                                null
                            } catch (e: Exception) {
                                e.message ?: e.toString()
                            }
                        },
                        onSaved = { backStack.removeLastOrNull() },
                    )
                }
                entry<MessageKey> { key ->
                    val current = account
                    if (current != null) {
                        MessageScreen(
                            account = current,
                            uid = key.uid,
                            repository = repository,
                            onDone = { backStack.removeLastOrNull() },
                        )
                    }
                }
            },
        )
    }
}
