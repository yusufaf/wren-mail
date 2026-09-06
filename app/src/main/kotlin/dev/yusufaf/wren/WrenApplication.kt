package dev.yusufaf.wren

import android.app.Application
import com.fsck.k9.mail.internet.BinaryTempFileBody
import dev.yusufaf.wren.account.AccountStore
import dev.yusufaf.wren.data.MailRepository
import dev.yusufaf.wren.data.WrenDatabase
import dev.yusufaf.wren.mailkit.MailService
import dev.yusufaf.wren.mailkit.WrenTrustedSocketFactory
import dev.yusufaf.wren.sync.SyncWorker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.thunderbird.legacy.logging.Log

class WrenApplication : Application() {

    val accountStore by lazy { AccountStore(this) }

    // Outlives every screen: hosts a triage archive's undo timer (see
    // MailRepository.archive) so it isn't cancelled by navigation away from
    // the inbox.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository by lazy {
        val db = WrenDatabase.create(this)
        MailRepository(
            db.inboxDao(),
            db.pendingOpDao(),
            MailService(WrenTrustedSocketFactory(File(filesDir, "ssl-keystore"))),
            applicationScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        BinaryTempFileBody.setTempDirectory(cacheDir)
        Log.backend = Log.Backend { priority, throwable, message ->
            val tag = "WrenMail"
            when (priority) {
                Log.Priority.VERBOSE -> android.util.Log.v(tag, message, throwable)
                Log.Priority.DEBUG -> android.util.Log.d(tag, message, throwable)
                Log.Priority.INFO -> android.util.Log.i(tag, message, throwable)
                Log.Priority.WARN -> android.util.Log.w(tag, message, throwable)
                Log.Priority.ERROR -> android.util.Log.e(tag, message, throwable)
            }
        }
        SyncWorker.schedule(this)
    }
}
