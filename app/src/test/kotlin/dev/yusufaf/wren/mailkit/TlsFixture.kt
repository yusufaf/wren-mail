package dev.yusufaf.wren.mailkit

import java.security.KeyStore
import java.security.cert.CertificateFactory
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

    /**
     * tls/localhost-ca-issued.pem, a 100-year cert for localhost signed by a
     * throwaway "Wren Test CA" whose key is not kept. Not self-signed, and not
     * backdated (its notBefore is when it was generated). Regenerate with:
     * keytool -genkeypair -alias ca -keyalg EC -groupname secp256r1
     * -sigalg SHA256withECDSA -dname "CN=Wren Test CA" -ext bc:c
     * -validity 36500 -storetype PKCS12 -keystore ca.p12 -storepass changeit
     * -keypass changeit
     * keytool -genkeypair -alias leaf -keyalg EC -groupname secp256r1
     * -sigalg SHA256withECDSA -dname CN=localhost -validity 36500
     * -storetype PKCS12 -keystore ca.p12 -storepass changeit -keypass changeit
     * keytool -certreq -alias leaf -keystore ca.p12 -storepass changeit
     * -file leaf.csr
     * keytool -gencert -alias ca -infile leaf.csr
     * -outfile localhost-ca-issued.pem -rfc
     * -ext "SAN=dns:localhost,ip:127.0.0.1" -validity 36500
     * -keystore ca.p12 -storepass changeit
     */
    val caIssuedCertificate: X509Certificate =
        TlsFixture::class.java.getResourceAsStream("/tls/localhost-ca-issued.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
}
