package dev.yusufaf.wren

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.fsck.k9.mail.ConnectionSecurity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import dev.yusufaf.wren.account.Account
import dev.yusufaf.wren.account.AccountStore
import dev.yusufaf.wren.mailkit.MailService
import dev.yusufaf.wren.ui.AccountSetupScreen
import dev.yusufaf.wren.ui.InboxScreen
import dev.yusufaf.wren.ui.InboxState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accountStore = AccountStore(applicationContext)
        val mailService = MailService()
        seedAccountFromIntentForDebug(accountStore, intent)
        setContent {
            MaterialTheme {
                WrenApp(accountStore, mailService)
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
        // first composition, avoiding a race with the boot-screen decision.
        runBlocking { accountStore.save(account) }
    }
}

private enum class Screen { Boot, Setup, Inbox }

@Composable
fun WrenApp(accountStore: AccountStore, mailService: MailService) {
    var screen by remember { mutableStateOf(Screen.Boot) }
    var account by remember { mutableStateOf<Account?>(null) }
    var inboxState by remember { mutableStateOf<InboxState>(InboxState.Loading) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        accountStore.account.collect { stored ->
            account = stored
            if (screen == Screen.Boot) {
                screen = if (stored == null) Screen.Setup else Screen.Inbox
            }
        }
    }

    fun refreshInbox() {
        val current = account ?: return
        inboxState = InboxState.Loading
        scope.launch {
            inboxState = try {
                InboxState.Ready(mailService.fetchInbox(current))
            } catch (e: Exception) {
                InboxState.Failed(e.message ?: e.toString())
            }
        }
    }

    LaunchedEffect(screen, account) {
        if (screen == Screen.Inbox && account != null) refreshInbox()
    }

    AppScaffold(timeText = { TimeText() }) {
        when (screen) {
            Screen.Boot -> Unit

            Screen.Setup -> AccountSetupScreen(
                initial = account,
                onValidateAndSave = { candidate ->
                    try {
                        mailService.checkSettings(candidate)
                        accountStore.save(candidate)
                        null
                    } catch (e: Exception) {
                        e.message ?: e.toString()
                    }
                },
                onSaved = { screen = Screen.Inbox },
            )

            Screen.Inbox -> InboxScreen(
                state = inboxState,
                onRefresh = ::refreshInbox,
                onOpenSettings = { screen = Screen.Setup },
            )
        }
    }
}
