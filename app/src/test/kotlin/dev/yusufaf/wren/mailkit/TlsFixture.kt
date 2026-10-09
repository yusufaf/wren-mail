package dev.yusufaf.wren.mailkit

import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * tls/localhost-self-signed.p12, a 100-year self-signed EC cert. Regenerate
 * with: keytool -genkeypair -alias server -keyalg EC -groupname secp256r1
 * -sigalg SHA256withECDSA -dname CN=localhost
 * -ext SAN=dns:localhost,ip:127.0.0.1 -validity 36500 -storetype PKCS12
 * -keystore localhost-self-signed.p12 -storepass changeit -keypass changeit
 */
internal object TlsFixture {
    const val PASSWORD = "changeit"

    val keyStore: KeyStore = KeyStore.getInstance("PKCS12").apply {
        TlsFixture::class.java.getResourceAsStream("/tls/localhost-self-signed.p12")!!.use {
            load(it, PASSWORD.toCharArray())
        }
    }

    val certificate: X509Certificate = keyStore.getCertificate("server") as X509Certificate
}
