package dev.yusufaf.wren.mailkit

import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The app's single entry point for trust changes. Going through here keeps the
 * UI out of the store and guarantees a revoke also drops the connections that
 * were established under the old exception.
 */
class TrustExceptions(
    private val socketFactory: WrenTrustedSocketFactory,
    private val mailService: MailService,
) {

    suspend fun list(): List<TrustException> = withContext(Dispatchers.IO) {
        socketFactory.trustExceptions().sortedWith(compareBy({ it.host }, { it.port }))
    }

    /** Throws CertificateException; see [WrenTrustedSocketFactory.addTrustException]. */
    suspend fun accept(host: String, port: Int, certificate: X509Certificate) {
        withContext(Dispatchers.IO) { socketFactory.addTrustException(host, port, certificate) }
    }

    /**
     * The order is load-bearing: remove the exception, then reset. Resetting
     * first would let a concurrent operation reconnect under the old
     * exception and pool that connection.
     *
     * The reset runs in `finally` because a persist failure (an
     * [java.io.IOException] from opening the keystore file) is thrown only
     * after the in-memory revoke took effect; the pool must still be dropped,
     * and the exception still reaches the caller. It is non-cancellable
     * because the caller's scope (the screen) can be cancelled mid-revoke, and
     * the reset may be queued behind a cold store's warm-up on the store
     * mutex; a cancelled reset would leave pre-revoke connections pooled.
     */
    suspend fun revoke(host: String, port: Int) {
        try {
            withContext(Dispatchers.IO) { socketFactory.removeTrustException(host, port) }
        } finally {
            withContext(NonCancellable) { mailService.resetConnections() }
        }
    }
}
