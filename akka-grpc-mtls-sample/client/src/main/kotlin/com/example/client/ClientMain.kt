package com.example.client

import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("ClientMain")

fun main(args: Array<String>) {
    val projectDir = File(System.getProperty("user.dir"))
    val rootCertsDir = if (File(projectDir, "certs").exists()) {
        File(projectDir, "certs")
    } else {
        File(projectDir.parentFile, "certs")
    }

    val host = System.getenv("SERVER_HOST") ?: "127.0.0.1"
    val port = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 8443
    val caCert = File(System.getenv("CA_CERT") ?: File(rootCertsDir, "ca.crt").absolutePath)

    val mode = args.firstOrNull() ?: "demo"

    when (mode) {
        "demo" -> runAutomatedDemoSuite(host, port, rootCertsDir, caCert)
        "stream" -> runStreamingAutoReconnectDemo(host, port, rootCertsDir, caCert)
        else -> runSingleClientCall(mode, host, port, rootCertsDir, caCert)
    }
}

private fun runAutomatedDemoSuite(
    host: String,
    port: Int,
    certsDir: File,
    caCert: File
) {
    logger.info("=========================================================")
    logger.info("STARTING AKKA gRPC PURE mTLS DEMO SUITE (NO POLICY)")
    logger.info("Target Akka gRPC server: {}:{}", host, port)
    logger.info("=========================================================")

    // --- TEST 1: alpha-client (Valid mTLS Cert) ---
    logger.info("\n--- [TEST 1] Testing 'alpha-client' (Valid cert signed by Root CA) ---")
    val alphaCert = File(certsDir, "alpha-client.crt")
    val alphaKey = File(certsDir, "alpha-client.pem")

    AkkaSignalClient(host, port, alphaCert, alphaKey, caCert).use { client ->
        val healthRes = client.getHealth("alpha-client")
        val signalRes = client.sendSignal("SIG-101", "CRITICAL", "Alpha payload via Akka gRPC")
        val telemetryRes = client.submitTelemetry("{\"cpu\": 45.2}")

        logger.info("\n--- [TEST 1b] Testing 'alpha-client' Bi-directional Akka Stream ---")
        client.startAutoReconnectingEventStream(maxEvents = 3, delayBetweenEventsMs = 500L)

        val alphaPassed = healthRes.isSuccess && signalRes.isSuccess && telemetryRes.isSuccess
        logger.info("Result for 'alpha-client': {}", if (alphaPassed) "PASSED (All calls authorized via mTLS)" else "FAILED")
    }

    // --- TEST 2: beta-client (Valid mTLS Cert - In policy sample SendSignal was denied, here ALL allowed) ---
    logger.info("\n--- [TEST 2] Testing 'beta-client' (Valid cert signed by Root CA) ---")
    logger.info("NOTE: Pure mTLS grants full access to any client with a valid certificate signed by CA.")
    val betaCert = File(certsDir, "beta-client.crt")
    val betaKey = File(certsDir, "beta-client.pem")

    AkkaSignalClient(host, port, betaCert, betaKey, caCert).use { client ->
        val healthRes = client.getHealth("beta-client")
        val signalRes = client.sendSignal("SIG-202", "WARN", "Beta payload via Akka gRPC")
        val telemetryRes = client.submitTelemetry("{\"memory\": 62.8}")

        logger.info("\n--- [TEST 2b] Testing 'beta-client' Bi-directional Akka Stream ---")
        client.startAutoReconnectingEventStream(maxEvents = 2, delayBetweenEventsMs = 500L)

        val betaPassed = healthRes.isSuccess && signalRes.isSuccess && telemetryRes.isSuccess
        logger.info(
            "Result for 'beta-client': GetHealth={}, SendSignal={}, SubmitTelemetry={}. Test: {}",
            if (healthRes.isSuccess) "ALLOWED" else "DENIED",
            if (signalRes.isSuccess) "ALLOWED (Pure mTLS)" else "DENIED",
            if (telemetryRes.isSuccess) "ALLOWED" else "DENIED",
            if (betaPassed) "PASSED" else "FAILED"
        )
    }

    // --- TEST 3: untrusted-client (Signed by untrusted rogue CA) ---
    logger.info("\n--- [TEST 3] Testing 'untrusted-client' (Signed by untrusted rogue CA) ---")
    logger.info("Verifies that the Akka HTTP server TLS handshake rejects untrusted certificates!")
    val unauthCert = File(certsDir, "untrusted-client.crt")
    val unauthKey = File(certsDir, "untrusted-client.pem")

    if (unauthCert.exists() && unauthKey.exists()) {
        AkkaSignalClient(host, port, unauthCert, unauthKey, caCert).use { client ->
            val healthRes = client.getHealth("untrusted-client")
            val signalRes = client.sendSignal("SIG-303", "INFO", "Untrusted payload")

            val rejectedAtTls = healthRes.isFailure && signalRes.isFailure
            logger.info(
                "Result for 'untrusted-client': GetHealth={}, SendSignal={}. Test: {}",
                if (healthRes.isFailure) "BLOCKED AT TLS HANDSHAKE (As Expected)" else "ALLOWED",
                if (signalRes.isFailure) "BLOCKED AT TLS HANDSHAKE (As Expected)" else "ALLOWED",
                if (rejectedAtTls) "PASSED (Rejected by Akka HTTP mTLS)" else "FAILED"
            )
        }
    } else {
        logger.warn("Skipping TEST 3: untrusted-client cert not found in certs directory")
    }

    logger.info("\n=========================================================")
    logger.info("AKKA gRPC PURE mTLS DEMO SUITE COMPLETE")
    logger.info("=========================================================")
}

private fun runStreamingAutoReconnectDemo(
    host: String,
    port: Int,
    certsDir: File,
    caCert: File
) {
    logger.info("=========================================================")
    logger.info("STARTING LIVE AKKA gRPC STREAM AUTO-RECONNECT DEMO")
    logger.info("Keep server running or restart it mid-stream to watch reconnect!")
    logger.info("=========================================================")

    val certFile = File(certsDir, "alpha-client.crt")
    val keyFile = File(certsDir, "alpha-client.pem")

    AkkaSignalClient(host, port, certFile, keyFile, caCert).use { client ->
        client.startAutoReconnectingEventStream(maxEvents = 30, delayBetweenEventsMs = 1500L)
    }
}

private fun runSingleClientCall(
    clientName: String,
    host: String,
    port: Int,
    certsDir: File,
    caCert: File
) {
    val certFile = File(certsDir, "$clientName.crt")
    val keyFile = File(certsDir, "$clientName.pem")

    logger.info("Executing client call using identity: {}", clientName)
    AkkaSignalClient(host, port, certFile, keyFile, caCert).use { client ->
        client.getHealth(clientName)
        client.startAutoReconnectingEventStream(maxEvents = 5, delayBetweenEventsMs = 1000L)
    }
}
