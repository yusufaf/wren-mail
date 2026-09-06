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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    // Not lifecycleScope: androidx.lifecycle:lifecycle-runtime-ktx isn't a
    // dependency here. Cancelled in onDestroy.
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    // Backgrounded: drop the pooled IMAP connection rather than hold an idle
    // socket open while the watch sleeps. The next call reconnects lazily.
    // NonCancellable: onDestroy can follow onStop immediately and cancels
    // activityScope — without this, a release still waiting on the store's
    // mutex would be cancelled before it ran, defeating the whole point.
    override fun onStop() {
        super.onStop()
        val app = application as WrenApplication
        activityScope.launch {
            withContext(NonCancellable) {
                app.repository.releaseConnections()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
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

/**
 * Below this age a return to the inbox renders the cache as-is rather than
 * re-hitting the network — otherwise every triage pop (archive/delete/mark
 * unread all leave the message screen) paid for a full sync on top of the
 * 15-minute background one. Becomes a setting later; hardcoded for now.
 */
private const val INBOX_STALE_AFTER_MS = 2 * 60 * 1000L

@Composable
fun WrenApp(accountStore: AccountStore, repository: MailRepository) {
    val backStack = rememberNavBackStack(InboxKey)
    var account by remember { mutableStateOf<Account?>(null) }
    var accountLoaded by remember { mutableStateOf(false) }
    val envelopes by repository.inbox.collectAsState(initial = null)
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var lastRefreshAt by remember { mutableStateOf(0L) }
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

    fun refreshInbox(force: Boolean = true) {
        val current = account ?: return
        if (refreshing) return
        if (!force && System.currentTimeMillis() - lastRefreshAt < INBOX_STALE_AFTER_MS) {
            // Cache is fresh enough to skip a full refetch, but a triage
            // action taken while offline still has a PendingOp waiting —
            // give it a chance to reach the server now, rather than making
            // it wait for the next stale window or the 15-minute worker.
            scope.launch { runCatching { repository.flushPendingOps(current) } }
            return
        }
        refreshing = true
        refreshError = null
        scope.launch {
            try {
                repository.refresh(current)
                lastRefreshAt = System.currentTimeMillis()
            } catch (e: Exception) {
                refreshError = e.message ?: e.toString()
            }
            refreshing = false
        }
    }

    // Refreshes on first load and whenever the account changes; a plain
    // return to the inbox (a triage pop or swipe-dismiss) only refreshes if
    // the cache has gone stale, since the cache already reflects the action
    // that sent us back here.
    val previousAccount = remember { mutableStateOf<Account?>(null) }
    LaunchedEffect(account, backStack.lastOrNull()) {
        if (account != null && backStack.lastOrNull() is InboxKey) {
            val accountChanged = previousAccount.value != account
            previousAccount.value = account
            refreshInbox(force = accountChanged)
        }
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
                        onRefresh = { refreshInbox(force = true) },
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
