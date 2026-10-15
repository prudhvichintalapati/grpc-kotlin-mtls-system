package com.example.server

import com.example.server.auth.MtlsIdentityInterceptor
import com.example.server.service.SignalServiceImpl
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress

class MtlsServer(
    private val port: Int,
    private val serverCertFile: File,
    private val serverKeyFile: File,
    private val caCertFile: File
) {
    companion object {
        private val logger = LoggerFactory.getLogger(MtlsServer::class.java)
    }

    private var server: Server? = null

    fun start() {
        // 1. Verify certificate files exist
        check(serverCertFile.exists()) { "Server cert file not found: ${serverCertFile.absolutePath}" }
        check(serverKeyFile.exists()) { "Server key file not found: ${serverKeyFile.absolutePath}" }
        check(caCertFile.exists()) { "CA cert file not found: ${caCertFile.absolutePath}" }

        // 2. Build Mutual TLS (mTLS) SSL Context for Netty
        val sslContext: SslContext = GrpcSslContexts.forServer(serverCertFile, serverKeyFile)
            .trustManager(caCertFile)
            .clientAuth(ClientAuth.REQUIRE) // Mutual TLS: Require client to present valid signed cert
            .build()

        // 3. Create and start gRPC Server (Pure mTLS, No Policy Interceptor)
        val identityInterceptor = MtlsIdentityInterceptor()
        val signalService = SignalServiceImpl()

        server = NettyServerBuilder.forAddress(InetSocketAddress("0.0.0.0", port))
            .sslContext(sslContext)
            .addService(signalService)
            .intercept(identityInterceptor)
            .build()
            .start()

        logger.info("==================================================")
        logger.info("Signal gRPC Server (mTLS-Only) started on port {}", port)
        logger.info("mTLS Enforced: YES (Trusting CA: {})", caCertFile.name)
        logger.info("Access Control: Pure mTLS Authentication (No Policy Rules)")
        logger.info("==================================================")

        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutting down gRPC Server...")
            this.stop()
            logger.info("gRPC Server shut down.")
        })
    }

    fun stop() {
        server?.shutdown()
    }

    fun blockUntilShutdown() {
        server?.awaitTermination()
    }
}

fun main(args: Array<String>) {
    val projectDir = File(System.getProperty("user.dir"))

    // Resolve certs directory
    val rootCertsDir = if (File(projectDir, "certs").exists()) {
        File(projectDir, "certs")
    } else {
        File(projectDir.parentFile, "certs")
    }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8443
    val serverCert = File(System.getenv("SERVER_CERT") ?: File(rootCertsDir, "server.crt").absolutePath)
    val serverKey = File(System.getenv("SERVER_KEY") ?: File(rootCertsDir, "server.pem").absolutePath)
    val caCert = File(System.getenv("CA_CERT") ?: File(rootCertsDir, "ca.crt").absolutePath)

    val server = MtlsServer(
        port = port,
        serverCertFile = serverCert,
        serverKeyFile = serverKey,
        caCertFile = caCert
    )

    server.start()
    server.blockUntilShutdown()
}
