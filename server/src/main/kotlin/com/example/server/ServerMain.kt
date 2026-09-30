package com.example.server

import com.example.server.auth.MtlsAuthInterceptor
import com.example.server.policy.PolicyEvaluator
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
    private val caCertFile: File,
    private val policyFile: File
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
        check(policyFile.exists()) { "Policy file not found: ${policyFile.absolutePath}" }

        // 2. Load policy evaluator
        val policyEvaluator = PolicyEvaluator.fromFile(policyFile)

        // 3. Build mTLS SSL Context for Netty
        val sslContext: SslContext = GrpcSslContexts.forServer(serverCertFile, serverKeyFile)
            .trustManager(caCertFile)
            .clientAuth(ClientAuth.REQUIRE) // Mutual TLS: Require client to present valid signed cert
            .build()

        // 4. Create and start gRPC Server
        val authInterceptor = MtlsAuthInterceptor(policyEvaluator)
        val signalService = SignalServiceImpl()

        server = NettyServerBuilder.forAddress(InetSocketAddress("0.0.0.0", port))
            .sslContext(sslContext)
            .addService(signalService)
            .intercept(authInterceptor)
            .build()
            .start()

        logger.info("==================================================")
        logger.info("Signal gRPC Server started successfully on port {}", port)
        logger.info("mTLS Enforced: YES (Trusting CA: {})", caCertFile.name)
        logger.info("Policy Loaded: {}", policyFile.absolutePath)
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
    
    // Resolve certs directory (can be in root project/certs or relative)
    val rootCertsDir = if (File(projectDir, "certs").exists()) {
        File(projectDir, "certs")
    } else {
        File(projectDir.parentFile, "certs")
    }

    // Resolve policy file
    val defaultPolicyFile = if (File(projectDir, "server/src/main/resources/policy.json").exists()) {
        File(projectDir, "server/src/main/resources/policy.json")
    } else if (File(projectDir, "src/main/resources/policy.json").exists()) {
        File(projectDir, "src/main/resources/policy.json")
    } else {
        File(rootCertsDir.parentFile, "server/src/main/resources/policy.json")
    }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8443
    val serverCert = File(System.getenv("SERVER_CERT") ?: File(rootCertsDir, "server.crt").absolutePath)
    val serverKey = File(System.getenv("SERVER_KEY") ?: File(rootCertsDir, "server.pem").absolutePath)
    val caCert = File(System.getenv("CA_CERT") ?: File(rootCertsDir, "ca.crt").absolutePath)
    val policyFile = File(System.getenv("POLICY_FILE") ?: defaultPolicyFile.absolutePath)

    val server = MtlsServer(
        port = port,
        serverCertFile = serverCert,
        serverKeyFile = serverKey,
        caCertFile = caCert,
        policyFile = policyFile
    )

    server.start()
    server.blockUntilShutdown()
}
