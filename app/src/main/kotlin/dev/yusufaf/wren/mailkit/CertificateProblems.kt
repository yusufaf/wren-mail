package dev.yusufaf.wren.mailkit

import java.security.GeneralSecurityException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLException
import javax.security.auth.x500.X500Principal
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier
import org.apache.hc.core5.net.InetAddressUtils

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

fun ConnectionFailure.UntrustedCertificate.namesHostOnlyAsCommonName(): Boolean =
    certificate.namesIpHostOnlyAsCommonName(host)

/**
 * True when [host] is an IP address, this certificate has no IP-address
 * alternative names, and one of its subject common names equals [host].
 *
 * The hostname verifier never matches an IP host against the common name, so
 * [problemsFor] still reports a mismatch here. This only lets the trust prompt
 * say why, instead of "not issued for" the host the subject shows.
 */
fun X509Certificate.namesIpHostOnlyAsCommonName(host: String): Boolean {
    if (!isIpLiteral(host)) return false
    val hasIpName = try {
        subjectAlternativeNames.orEmpty().any { it[0] == SAN_IP_ADDRESS }
    } catch (_: CertificateParsingException) {
        return false
    }
    if (hasIpName) return false
    val address = host.removeSurrounding("[", "]")
    return commonNames(subjectX500Principal.getName(X500Principal.RFC2253))
        .any { it.equals(address, ignoreCase = true) }
}

/** Classifies [host] the way `DefaultHostnameVerifier.determineHostFormat` does. */
internal fun isIpLiteral(host: String): Boolean =
    InetAddressUtils.isIPv4(host) || InetAddressUtils.isIPv6(host.removeSurrounding("[", "]"))

/** The CN values in an RFC 2253 distinguished name; an escaped separator doesn't split a value. */
internal fun commonNames(distinguishedName: String): List<String> =
    distinguishedName.split(Regex("""(?<!\\)[,+]"""))
        .filter { it.substringBefore('=').trim().equals("CN", ignoreCase = true) }
        .map { it.substringAfter('=').trim() }

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
