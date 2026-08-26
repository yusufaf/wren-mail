package dev.yusufaf.wren.account

import com.fsck.k9.mail.ConnectionSecurity

/**
 * A single IMAP account. v1 supports exactly one, authenticated with a
 * username and (app) password — no OAuth.
 */
data class Account(
    val host: String,
    val port: Int,
    val security: ConnectionSecurity,
    val username: String,
    val password: String,
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && port in 1..65535 && username.isNotBlank() && password.isNotEmpty()

    companion object {
        fun defaultPortFor(security: ConnectionSecurity): Int = when (security) {
            ConnectionSecurity.SSL_TLS_REQUIRED -> 993
            else -> 143
        }
    }
}
