package dev.yusufaf.wren.mailkit

import java.security.MessageDigest
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
)

fun X509Certificate.toCertificateInfo(): CertificateInfo = CertificateInfo(
    subject = subjectX500Principal.getName(X500Principal.RFC2253),
    issuer = issuerX500Principal.getName(X500Principal.RFC2253),
    sha256Fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(encoded)
        .joinToString(":") { "%02X".format(it) },
    validFrom = notBefore,
    validUntil = notAfter,
)
