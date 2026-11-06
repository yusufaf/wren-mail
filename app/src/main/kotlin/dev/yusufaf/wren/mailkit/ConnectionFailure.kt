package dev.yusufaf.wren.mailkit

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.ssl.CertificateChainExtractor
import dev.yusufaf.wren.account.Account
import java.io.IOException
import java.security.cert.X509Certificate
import net.thunderbird.core.common.exception.MessagingException

/** A connection error, split into the one the user can act on and the rest. */
sealed interface ConnectionFailure {
    val message: String

    /** The server's certificate failed validation; [certificate] is its leaf. */
    data class UntrustedCertificate(
        val host: String,
        val port: Int,
        val certificate: X509Certificate,
    ) : ConnectionFailure {
        override val message: String get() = MESSAGE

        companion object {
            const val MESSAGE = "Server certificate not trusted"
        }
    }

    data class Other(override val message: String) : ConnectionFailure
}

/**
 * Host and port come from [account] because that is the key the trust manager
 * checks (`getTrustManagerForDomain(account.host, account.port)`), so it is also
 * the key a trust exception has to be stored under. The extractor walks the
 * cause chain, so it recognises both the CertificateValidationException from
 * opening a connection and an SSLException a folder operation wrapped as
 * "IO Error".
 */
fun Throwable.toConnectionFailure(account: Account): ConnectionFailure {
    val leaf = CertificateChainExtractor.extract(this)?.firstOrNull()
    return if (leaf != null) {
        ConnectionFailure.UntrustedCertificate(account.host, account.port, leaf)
    } else {
        ConnectionFailure.Other(message ?: toString())
    }
}

/**
 * True when this failure says nothing about the operation that raised it — the
 * connection, login or TLS handshake failed — so the next operation would fail
 * the same way. A server's NO or BAD to one command arrives as a
 * MessagingException with isPermanentFailure set (k9's
 * NegativeImapResponseException) and is specific to that command, as is any
 * other unexpected exception. The exception is a rejected login: k9 only wraps
 * it in AuthenticationFailedException when the server gives no response code
 * or AUTHENTICATIONFAILED, and passes it through as a plain permanent NO for
 * codes like [ALERT] or [UNAVAILABLE]. Treating that as one op's rejection
 * would log in once per queued op, which can trip a provider's lockout.
 */
fun Throwable.isConnectionLevel(): Boolean = when {
    this is IOException -> true
    this !is MessagingException -> false
    else -> !isPermanentFailure || isLoginRejection()
}

/**
 * True when the server turned down the login itself, so anything else sent
 * with the same credentials will be turned down too, and every retry counts
 * toward the provider's lockout. Covers AuthenticationFailedException and the
 * plain permanent NO that k9 passes through for a login rejected with a
 * response code other than AUTHENTICATIONFAILED (see [isConnectionLevel]).
 */
fun Throwable.isLoginRejection(): Boolean = when {
    this is AuthenticationFailedException -> true
    this is MessagingException && isPermanentFailure ->
        message?.let { msg -> LOGIN_COMMANDS.any { msg.startsWith(it) } } == true
    else -> false
}

// ImapResponseParser words a NO/BAD as "Command: <command>; response: ...",
// and RealImapConnection logs LOGIN as *sensitive* unless sensitive debug
// logging is on (LOGIN is the only command it marks sensitive).
private val LOGIN_COMMANDS = listOf("Command: *sensitive*", "Command: LOGIN", "Command: AUTHENTICATE")
