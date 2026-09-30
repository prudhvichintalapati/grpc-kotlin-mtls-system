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

    val targetClient = args.firstOrNull() ?: "demo"

    if (targetClient == "demo") {
        runAutomatedDemoSuite(host, port, rootCertsDir, caCert)
    } else {
        runSingleClientCall(targetClient, host, port, rootCertsDir, caCert)
    }
}

private suspend fun runAutomatedDemoSuite(
    host: String,
    port: Int,
    certsDir: File,
    caCert: File
) {
    logger.info("=========================================================")
    logger.info("STARTING mTLS RBAC POLICY AUTHORIZATION DEMO SUITE")
    logger.info("Server endpoint: {}:{}", host, port)
    logger.info("=========================================================")

    // --- TEST CASE 1: alpha-client ---
    logger.info("\n--- [TEST 1] Testing 'alpha-client' (Full Privileges in policy.json) ---")
    val alphaCert = File(certsDir, "alpha-client.crt")
    val alphaKey = File(certsDir, "alpha-client.pem")

    SignalClient(host, port, alphaCert, alphaKey, caCert).use { client ->
        val healthRes = client.getHealth("alpha-client")
        val signalRes = client.sendSignal("SIG-101", "CRITICAL", "Alpha payload data")
        val telemetryRes = client.submitTelemetry("{\"cpu\": 42.5}")

        val alphaPassed = healthRes.isSuccess && signalRes.isSuccess && telemetryRes.isSuccess
        logger.info("Result for 'alpha-client': {}", if (alphaPassed) "PASSED (All calls authorized)" else "FAILED")
    }

    // --- TEST CASE 2: beta-client ---
    logger.info("\n--- [TEST 2] Testing 'beta-client' (Restricted: GetHealth allowed, SendSignal DENIED) ---")
    val betaCert = File(certsDir, "beta-client.crt")
    val betaKey = File(certsDir, "beta-client.pem")

    SignalClient(host, port, betaCert, betaKey, caCert).use { client ->
        val healthRes = client.getHealth("beta-client")
        val signalRes = client.sendSignal("SIG-202", "WARN", "Beta payload data")

        val betaPassed = healthRes.isSuccess && signalRes.isFailure
        logger.info(
            "Result for 'beta-client': GetHealth={}, SendSignal={}. Test: {}",
            if (healthRes.isSuccess) "ALLOWED" else "DENIED",
            if (signalRes.isFailure) "DENIED BY POLICY (As Expected)" else "ALLOWED (Unexpected)",
            if (betaPassed) "PASSED" else "FAILED"
        )
    }

    // --- TEST CASE 3: unauthorized-client ---
    logger.info("\n--- [TEST 3] Testing 'unauthorized-client' (No permissions in policy.json) ---")
    val unauthCert = File(certsDir, "unauthorized-client.crt")
    val unauthKey = File(certsDir, "unauthorized-client.pem")

    SignalClient(host, port, unauthCert, unauthKey, caCert).use { client ->
        val healthRes = client.getHealth("unauthorized-client")
        val signalRes = client.sendSignal("SIG-303", "INFO", "Unauthorized payload")

        val unauthPassed = healthRes.isFailure && signalRes.isFailure
        logger.info(
            "Result for 'unauthorized-client': GetHealth={}, SendSignal={}. Test: {}",
            if (healthRes.isFailure) "DENIED BY POLICY (As Expected)" else "ALLOWED",
            if (signalRes.isFailure) "DENIED BY POLICY (As Expected)" else "ALLOWED",
            if (unauthPassed) "PASSED" else "FAILED"
        )
    }

    logger.info("\n=========================================================")
    logger.info("mTLS POLICY AUTHORIZATION DEMO SUITE COMPLETE")
    logger.info("=========================================================")
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

    logger.info("Executing client call using identity: {}", clientName)
    SignalClient(host, port, certFile, keyFile, caCert).use { client ->
        client.getHealth(clientName)
        client.sendSignal("SIG-CUSTOM", "INFO", "Custom signal call from $clientName")
    }
}
