package dev.yusufaf.wren.mailkit

import com.fsck.k9.mail.ssl.CertificateChainExtractor
import dev.yusufaf.wren.account.Account
import java.security.cert.X509Certificate

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
