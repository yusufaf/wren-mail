package net.thunderbird.legacy.logging

/**
 * Minimal replacement for Thunderbird's static logging facade, providing only the
 * static methods the vendored mail modules call. The host app installs a backend
 * (e.g. android.util.Log) at startup; until then messages go to stdout.
 */
@Suppress("TooManyFunctions")
object Log {

    fun interface Backend {
        fun log(priority: Priority, throwable: Throwable?, message: String)
    }

    enum class Priority { VERBOSE, DEBUG, INFO, WARN, ERROR }

    @Volatile
    var backend: Backend = Backend { priority, throwable, message ->
        println("[${priority.name}] $message" + (throwable?.let { " ($it)" } ?: ""))
    }

    @JvmStatic
    fun v(message: String?, vararg args: Any?) = log(Priority.VERBOSE, null, message, args)

    @JvmStatic
    fun v(t: Throwable?, message: String?, vararg args: Any?) = log(Priority.VERBOSE, t, message, args)

    @JvmStatic
    fun d(message: String?, vararg args: Any?) = log(Priority.DEBUG, null, message, args)

    @JvmStatic
    fun d(t: Throwable?, message: String?, vararg args: Any?) = log(Priority.DEBUG, t, message, args)

    @JvmStatic
    fun i(message: String?, vararg args: Any?) = log(Priority.INFO, null, message, args)

    @JvmStatic
    fun i(t: Throwable?, message: String?, vararg args: Any?) = log(Priority.INFO, t, message, args)

    @JvmStatic
    fun w(message: String?, vararg args: Any?) = log(Priority.WARN, null, message, args)

    @JvmStatic
    fun w(t: Throwable?, message: String?, vararg args: Any?) = log(Priority.WARN, t, message, args)

    @JvmStatic
    fun e(message: String?, vararg args: Any?) = log(Priority.ERROR, null, message, args)

    @JvmStatic
    fun e(t: Throwable?, message: String?, vararg args: Any?) = log(Priority.ERROR, t, message, args)

    @Suppress("SpreadOperator", "TooGenericExceptionCaught")
    private fun log(priority: Priority, throwable: Throwable?, message: String?, args: Array<out Any?>) {
        val formatted = if (message == null) {
            ""
        } else if (args.isEmpty()) {
            message
        } else {
            try {
                String.format(message, *args)
            } catch (e: Exception) {
                "$message (Error formatting message: $e, args: ${args.joinToString()})"
            }
        }
        backend.log(priority, throwable, formatted)
    }
}
