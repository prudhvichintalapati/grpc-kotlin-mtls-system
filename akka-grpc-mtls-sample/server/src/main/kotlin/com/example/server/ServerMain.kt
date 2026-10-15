package com.example.server

import akka.actor.ActorSystem
import akka.http.javadsl.Http
import akka.http.javadsl.ServerBinding
import com.example.server.service.SignalServiceImpl
import com.example.server.tls.TlsHelper
import com.example.signal.v1.SignalServiceHandlerFactory
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CompletionStage

class AkkaMtlsServer(
    private val port: Int,
    private val serverCertFile: File,
    private val serverKeyFile: File,
    private val caCertFile: File
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AkkaMtlsServer::class.java)
    }

    private var system: ActorSystem? = null
    private var binding: CompletionStage<ServerBinding>? = null

    fun start() {
        check(serverCertFile.exists()) { "Server cert file not found: ${serverCertFile.absolutePath}" }
        check(serverKeyFile.exists()) { "Server key file not found: ${serverKeyFile.absolutePath}" }
        check(caCertFile.exists()) { "CA cert file not found: ${caCertFile.absolutePath}" }

        val actorSystem = ActorSystem.create("AkkaMtlsServer")
        system = actorSystem

        val serviceImpl = SignalServiceImpl(actorSystem)
        val handler = SignalServiceHandlerFactory.create(serviceImpl, actorSystem)
        val httpsContext = TlsHelper.createServerHttpsContext(serverCertFile, serverKeyFile, caCertFile)

        binding = Http.get(actorSystem)
            .newServerAt("0.0.0.0", port)
            .enableHttps(httpsContext)
            .bind(handler)

        binding?.thenAccept { b ->
            val actualPort = b.localAddress().port
            logger.info("==================================================")
            logger.info("Akka gRPC Server (mTLS-Only) started on port {}", actualPort)
            logger.info("mTLS Enforced: YES (SSLEngine.needClientAuth = true, Trusting CA: {})", caCertFile.name)
            logger.info("Engine: Akka HTTP (HTTP/2) + Akka Streams")
            logger.info("Access Control: Pure mTLS Authentication (No Policy Rules)")
            logger.info("==================================================")
        }?.exceptionally { ex ->
            logger.error("Failed to bind Akka gRPC server: ${ex.message}", ex)
            null
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutting down Akka gRPC Server...")
            this.stop()
            logger.info("Akka gRPC Server shut down.")
        })
    }

    fun stop() {
        binding?.thenCompose { it.unbind() }?.thenAccept {
            system?.terminate()
        }
    }

    fun blockUntilShutdown() {
        system?.whenTerminated?.toCompletableFuture()?.join()
    }
}

fun main(args: Array<String>) {
    val projectDir = File(System.getProperty("user.dir"))

    val rootCertsDir = if (File(projectDir, "certs").exists()) {
        File(projectDir, "certs")
    } else {
        File(projectDir.parentFile, "certs")
    }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8443
    val serverCert = File(System.getenv("SERVER_CERT") ?: File(rootCertsDir, "server.crt").absolutePath)
    val serverKey = File(System.getenv("SERVER_KEY") ?: File(rootCertsDir, "server.pem").absolutePath)
    val caCert = File(System.getenv("CA_CERT") ?: File(rootCertsDir, "ca.crt").absolutePath)

    val server = AkkaMtlsServer(
        port = port,
        serverCertFile = serverCert,
        serverKeyFile = serverKey,
        caCertFile = caCert
    )

    server.start()
    server.blockUntilShutdown()
}
