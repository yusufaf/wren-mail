package dev.yusufaf.wren

import android.app.Application
import com.fsck.k9.mail.internet.BinaryTempFileBody
import dev.yusufaf.wren.account.AccountStore
import dev.yusufaf.wren.data.MailRepository
import dev.yusufaf.wren.data.WrenDatabase
import dev.yusufaf.wren.mailkit.MailService
import dev.yusufaf.wren.sync.SyncWorker
import net.thunderbird.legacy.logging.Log

class WrenApplication : Application() {

    val accountStore by lazy { AccountStore(this) }
    val repository by lazy { MailRepository(WrenDatabase.create(this), MailService()) }

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
