package com.example.server.tls

import akka.http.javadsl.ConnectionContext
import akka.http.javadsl.HttpsConnectionContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory

object TlsHelper {
    private val logger = LoggerFactory.getLogger(TlsHelper::class.java)

    /**
     * Creates an Akka HTTP HttpsConnectionContext that enforces Mutual TLS (mTLS).
     * The SSLEngine is configured with needClientAuth = true, meaning incoming connections
     * that fail to provide a certificate signed by the trusted CA will be aborted during
     * the TLS handshake.
     */
    fun createServerHttpsContext(
        serverCertFile: File,
        serverKeyFile: File,
        caCertFile: File
    ): HttpsConnectionContext {
        val sslContext = buildSslContext(serverCertFile, serverKeyFile, caCertFile)

        return ConnectionContext.httpsServer {
            val engine: SSLEngine = sslContext.createSSLEngine()
            engine.useClientMode = false
            engine.needClientAuth = true // Enforces mTLS: Requires valid client certificate signed by trusted CA
            engine
        }
    }

    fun buildSslContext(
        certFile: File,
        keyFile: File,
        caCertFile: File
    ): SSLContext {
        val cf = CertificateFactory.getInstance("X.509")

        // 1. TrustStore: Trust the Root CA
        val caCert = FileInputStream(caCertFile).use { cf.generateCertificate(it) as X509Certificate }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("trusted-ca", caCert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }

        // 2. KeyStore: Entity Certificate and Private Key
        val entityCert = FileInputStream(certFile).use { cf.generateCertificate(it) as X509Certificate }
        val privateKey = loadPrivateKey(keyFile)

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("entity-identity", privateKey, "password".toCharArray(), arrayOf(entityCert, caCert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, "password".toCharArray())
        }

        // 3. Initialize TLS context
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, tmf.trustManagers, null)
        return sslContext
    }

    private fun loadPrivateKey(keyFile: File): PrivateKey {
        val keyPem = keyFile.readText()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(keyPem)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(spec)
    }
}
