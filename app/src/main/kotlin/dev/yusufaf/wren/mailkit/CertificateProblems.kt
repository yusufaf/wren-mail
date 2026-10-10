package dev.yusufaf.wren.mailkit

import java.security.GeneralSecurityException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLException
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier

/** Why a certificate failed validation, most serious first. */
enum class CertificateProblem {
    HOSTNAME_MISMATCH,
    EXPIRED,
    NOT_YET_VALID,
    SELF_SIGNED,
    UNTRUSTED_ISSUER,
}

// The verifier the trust manager uses (TrustManagerFactory), so the prompt and
// the trust manager can't disagree about the host.
private val hostnameVerifier = DefaultHostnameVerifier()

/**
 * Works out why this leaf certificate would be rejected for [host].
 *
 * This is derived here rather than reported by the trust manager because the
 * trust manager skips its hostname check once the chain has failed, so it can't
 * say "wrong host" for a self-signed or untrusted certificate.
 *
 * The leaf alone can't show whether the chain was trusted, so
 * [CertificateProblem.UNTRUSTED_ISSUER] is only a fallback: if none of the
 * leaf-level problems apply, the chain is what the trust manager rejected. It is
 * left out when another problem is present rather than guessed.
 */
fun X509Certificate.problemsFor(host: String, now: Date = Date()): List<CertificateProblem> {
    val problems = buildList {
        if (!matchesHost(host)) add(CertificateProblem.HOSTNAME_MISMATCH)
        if (now.after(notAfter)) add(CertificateProblem.EXPIRED)
        if (now.before(notBefore)) add(CertificateProblem.NOT_YET_VALID)
        if (isSelfSigned()) add(CertificateProblem.SELF_SIGNED)
    }
    return problems.ifEmpty { listOf(CertificateProblem.UNTRUSTED_ISSUER) }
}

fun ConnectionFailure.UntrustedCertificate.problems(now: Date = Date()): List<CertificateProblem> =
    certificate.problemsFor(host, now)

private fun X509Certificate.matchesHost(host: String): Boolean = try {
    hostnameVerifier.verify(host, this)
    true
} catch (_: SSLException) {
    false
}

private fun X509Certificate.isSelfSigned(): Boolean {
    if (subjectX500Principal != issuerX500Principal) return false
    return try {
        verify(publicKey)
        true
    } catch (_: GeneralSecurityException) {
        false
    }
}
