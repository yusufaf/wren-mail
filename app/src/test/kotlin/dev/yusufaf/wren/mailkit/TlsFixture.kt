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
    val caIssuedCertificate: X509Certificate = pemCertificate("localhost-ca-issued.pem")

    /**
     * tls/localhost-cn-only.pem, a 100-year self-signed cert for CN=localhost
     * with no subject alternative name extension, like old home-server certs.
     * The key is not kept. Regenerate with:
     * keytool -genkeypair -alias server -keyalg EC -groupname secp256r1
     * -sigalg SHA256withECDSA -dname CN=localhost -validity 36500
     * -storetype PKCS12 -keystore cn.p12 -storepass changeit -keypass changeit
     * keytool -exportcert -alias server -keystore cn.p12 -storepass changeit
     * -rfc -file localhost-cn-only.pem
     */
    val cnOnlyCertificate: X509Certificate = pemCertificate("localhost-cn-only.pem")

    /**
     * tls/ip-cn-only.pem, like [cnOnlyCertificate] but with CN=127.0.0.1.
     * Regenerate with the same commands, using -dname CN=127.0.0.1, a new
     * keystore (-keystore ip.p12, so the first alias isn't reused) and
     * -file ip-cn-only.pem.
     */
    val ipCnOnlyCertificate: X509Certificate = pemCertificate("ip-cn-only.pem")

    /**
     * tls/localhost-other-san.pem, a 100-year self-signed cert with
     * CN=localhost but a single DNS alternative name, other.example, so the CN
     * matches localhost and the SAN does not. The key is not kept. Regenerate
     * with:
     * keytool -genkeypair -alias server -keyalg EC -groupname secp256r1
     * -sigalg SHA256withECDSA -dname CN=localhost -ext SAN=dns:other.example
     * -validity 36500 -storetype PKCS12 -keystore san.p12 -storepass changeit
     * -keypass changeit
     * keytool -exportcert -alias server -keystore san.p12 -storepass changeit
     * -rfc -file localhost-other-san.pem
     */
    val otherSanCertificate: X509Certificate = pemCertificate("localhost-other-san.pem")

    /**
     * tls/localhost-ip-san-only.pem, like [otherSanCertificate] but its only
     * alternative name is the IP address 127.0.0.1, so there is no DNS name to
     * take precedence over the CN. Regenerate with the same commands, using
     * -ext SAN=ip:127.0.0.1, a new keystore (-keystore ipsan.p12, so the first
     * alias isn't reused) and -file localhost-ip-san-only.pem.
     */
    val ipSanOnlyCertificate: X509Certificate = pemCertificate("localhost-ip-san-only.pem")

    /**
     * tls/ip-cn-ip-san.pem, like [ipCnOnlyCertificate] but also with the
     * IP alternative name 127.0.0.1, so its CN equals an IP host that a
     * SAN already names. Regenerate with the same commands, using
     * -ext SAN=ip:127.0.0.1, a new keystore (-keystore ipcnsan.p12, so the
     * first alias isn't reused) and -file ip-cn-ip-san.pem.
     */
    val ipCnIpSanCertificate: X509Certificate = pemCertificate("ip-cn-ip-san.pem")

    private fun pemCertificate(name: String): X509Certificate =
        TlsFixture::class.java.getResourceAsStream("/tls/$name")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
}
