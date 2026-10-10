package dev.yusufaf.wren.mailkit

import java.security.MessageDigest
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/** What the user needs to see to decide whether to trust a certificate. */
data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val sha256Fingerprint: String,
    val validFrom: Date,
    val validUntil: Date,
    /** The host names and IP addresses the certificate says it is for, so a mismatch can be checked by eye. */
    val names: List<String>,
)

fun X509Certificate.toCertificateInfo(): CertificateInfo = CertificateInfo(
    subject = subjectX500Principal.getName(X500Principal.RFC2253),
    issuer = issuerX500Principal.getName(X500Principal.RFC2253),
    sha256Fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(encoded)
        .joinToString(":") { "%02X".format(it) },
    validFrom = notBefore,
    validUntil = notAfter,
    names = alternativeNames(),
)

private const val SAN_DNS_NAME = 2
private const val SAN_IP_ADDRESS = 7

private fun X509Certificate.alternativeNames(): List<String> = try {
    subjectAlternativeNames.orEmpty().mapNotNull { entry ->
        (entry[1] as? String)?.takeIf { entry[0] == SAN_DNS_NAME || entry[0] == SAN_IP_ADDRESS }
    }
} catch (_: CertificateParsingException) {
    emptyList()
}
