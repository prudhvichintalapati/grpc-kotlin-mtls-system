package com.example.client

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("ClientMain")

fun main(args: Array<String>) = runBlocking {
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

private suspend fun runAutomatedDemoSuite(
    host: String,
    port: Int,
    certsDir: File,
    caCert: File
) {
    logger.info("=========================================================")
    logger.info("STARTING PURE mTLS AUTHENTICATION DEMO SUITE (NO POLICY)")
    logger.info("Server endpoint: {}:{}", host, port)
    logger.info("=========================================================")

    // --- TEST CASE 1: alpha-client (Valid mTLS Cert) ---
    logger.info("\n--- [TEST 1] Testing 'alpha-client' (Valid certificate signed by trusted CA) ---")
    val alphaCert = File(certsDir, "alpha-client.crt")
    val alphaKey = File(certsDir, "alpha-client.pem")

    SignalClient(host, port, alphaCert, alphaKey, caCert).use { client ->
        val healthRes = client.getHealth("alpha-client")
        val signalRes = client.sendSignal("SIG-101", "CRITICAL", "Alpha payload data")
        val telemetryRes = client.submitTelemetry("{\"cpu\": 42.5}")

        logger.info("\n--- [TEST 1b] Testing 'alpha-client' Bi-directional Event Stream ---")
        client.startAutoReconnectingEventStream(maxEvents = 3, delayBetweenEventsMs = 500L)

        val alphaPassed = healthRes.isSuccess && signalRes.isSuccess && telemetryRes.isSuccess
        logger.info("Result for 'alpha-client': {}", if (alphaPassed) "PASSED (All calls authorized via mTLS)" else "FAILED")
    }

    // --- TEST CASE 2: beta-client (Valid mTLS Cert - In policy sample SendSignal was denied, here ALL calls allowed) ---
    logger.info("\n--- [TEST 2] Testing 'beta-client' (Valid certificate signed by trusted CA) ---")
    logger.info("NOTE: In the policy sample, 'beta-client' was restricted by policy.json.")
    logger.info("In this mTLS-only sample, all authenticated mTLS clients have full access!")
    val betaCert = File(certsDir, "beta-client.crt")
    val betaKey = File(certsDir, "beta-client.pem")

    SignalClient(host, port, betaCert, betaKey, caCert).use { client ->
        val healthRes = client.getHealth("beta-client")
        val signalRes = client.sendSignal("SIG-202", "WARN", "Beta payload data")
        val telemetryRes = client.submitTelemetry("{\"memory\": 68.1}")

        logger.info("\n--- [TEST 2b] Testing 'beta-client' Bi-directional Event Stream ---")
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

    // --- TEST CASE 3: untrusted-client (Certificate NOT signed by trusted CA) ---
    logger.info("\n--- [TEST 3] Testing 'untrusted-client' (Signed by untrusted/rogue CA) ---")
    logger.info("Demonstrates pure mTLS defense: TLS Handshake must FAIL at transport layer!")
    val unauthCert = File(certsDir, "untrusted-client.crt")
    val unauthKey = File(certsDir, "untrusted-client.pem")

    if (unauthCert.exists() && unauthKey.exists()) {
        SignalClient(host, port, unauthCert, unauthKey, caCert).use { client ->
            val healthRes = client.getHealth("untrusted-client")
            val signalRes = client.sendSignal("SIG-303", "INFO", "Untrusted payload")

            val rejectedAtTls = healthRes.isFailure && signalRes.isFailure
            logger.info(
                "Result for 'untrusted-client': GetHealth={}, SendSignal={}. Test: {}",
                if (healthRes.isFailure) "BLOCKED AT TLS HANDSHAKE (As Expected)" else "ALLOWED",
                if (signalRes.isFailure) "BLOCKED AT TLS HANDSHAKE (As Expected)" else "ALLOWED",
                if (rejectedAtTls) "PASSED (Rejected by mTLS verification)" else "FAILED"
            )
        }
    } else {
        logger.warn("Skipping TEST 3: untrusted-client cert not found in certs directory")
    }

    logger.info("\n=========================================================")
    logger.info("PURE mTLS AUTHENTICATION DEMO SUITE COMPLETE")
    logger.info("=========================================================")
}

private suspend fun runStreamingAutoReconnectDemo(
    host: String,
    port: Int,
    certsDir: File,
    caCert: File
) {
    logger.info("=========================================================")
    logger.info("STARTING LIVE gRPC STREAM AUTO-RECONNECT DEMO (mTLS-Only)")
    logger.info("Keep server running or restart it mid-stream to watch reconnect!")
    logger.info("=========================================================")

    val certFile = File(certsDir, "alpha-client.crt")
    val keyFile = File(certsDir, "alpha-client.pem")

    SignalClient(host, port, certFile, keyFile, caCert).use { client ->
        client.startAutoReconnectingEventStream(maxEvents = 30, delayBetweenEventsMs = 1500L)
    }
}

private suspend fun runSingleClientCall(
    clientName: String,
    host: String,
    port: Int,
    certsDir: File,
    caCert: File
) {
    val certFile = File(certsDir, "$clientName.crt")
    val keyFile = File(certsDir, "$clientName.pem")

    logger.info("Executing client call using mTLS identity: {}", clientName)
    SignalClient(host, port, certFile, keyFile, caCert).use { client ->
        client.getHealth(clientName)
        client.startAutoReconnectingEventStream(maxEvents = 5, delayBetweenEventsMs = 1000L)
    }
}
