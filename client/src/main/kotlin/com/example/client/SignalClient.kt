package com.example.client

import com.example.signal.v1.HealthCheckRequest
import com.example.signal.v1.HealthCheckResponse
import com.example.signal.v1.SignalRequest
import com.example.signal.v1.SignalResponse
import com.example.signal.v1.SignalServiceGrpcKt
import com.example.signal.v1.TelemetryRequest
import com.example.signal.v1.TelemetryResponse
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit

class SignalClient(
    private val host: String,
    private val port: Int,
    private val clientCertFile: File,
    private val clientKeyFile: File,
    private val caCertFile: File
) : Closeable {

    companion object {
        private val logger = LoggerFactory.getLogger(SignalClient::class.java)
    }

    private val channel: ManagedChannel
    private val stub: SignalServiceGrpcKt.SignalServiceCoroutineStub

    init {
        check(clientCertFile.exists()) { "Client cert not found: ${clientCertFile.absolutePath}" }
        check(clientKeyFile.exists()) { "Client key not found: ${clientKeyFile.absolutePath}" }
        check(caCertFile.exists()) { "CA cert not found: ${caCertFile.absolutePath}" }

        // Build mTLS client SslContext presenting client certificate and trusting Root CA
        val sslContext: SslContext = GrpcSslContexts.forClient()
            .keyManager(clientCertFile, clientKeyFile)
            .trustManager(caCertFile)
            .build()

        channel = NettyChannelBuilder.forAddress(host, port)
            .sslContext(sslContext)
            .overrideAuthority("localhost") // Matches server certificate SAN/CN
            .build()

        stub = SignalServiceGrpcKt.SignalServiceCoroutineStub(channel)
    }

    suspend fun sendSignal(id: String, type: String, payload: String): Result<SignalResponse> {
        val request = SignalRequest.newBuilder()
            .setSignalId(id)
            .setSignalType(type)
            .setPayload(payload)
            .setTimestamp(System.currentTimeMillis())
            .build()

        return runCatching {
            stub.sendSignal(request)
        }.onSuccess { response ->
            logger.info("SendSignal SUCCESS: [${response.statusCode}] ${response.message}")
        }.onFailure { ex ->
            if (ex is StatusRuntimeException) {
                logger.warn("SendSignal DENIED/FAILED [${ex.status.code}]: ${ex.status.description}")
            } else {
                logger.error("SendSignal ERROR: ${ex.message}", ex)
            }
        }
    }

    suspend fun getHealth(clientName: String): Result<HealthCheckResponse> {
        val request = HealthCheckRequest.newBuilder()
            .setClientName(clientName)
            .build()

        return runCatching {
            stub.getHealth(request)
        }.onSuccess { response ->
            logger.info("GetHealth SUCCESS: status={}, uptime={}s, serverTime={}", response.status, response.uptimeSeconds, response.serverTime)
        }.onFailure { ex ->
            if (ex is StatusRuntimeException) {
                logger.warn("GetHealth DENIED/FAILED [${ex.status.code}]: ${ex.status.description}")
            } else {
                logger.error("GetHealth ERROR: ${ex.message}", ex)
            }
        }
    }

    suspend fun submitTelemetry(metricsJson: String): Result<TelemetryResponse> {
        val request = TelemetryRequest.newBuilder()
            .setMetricsJson(metricsJson)
            .build()

        return runCatching {
            stub.submitTelemetry(request)
        }.onSuccess { response ->
            logger.info("SubmitTelemetry SUCCESS: accepted {} items", response.itemsAccepted)
        }.onFailure { ex ->
            if (ex is StatusRuntimeException) {
                logger.warn("SubmitTelemetry DENIED/FAILED [${ex.status.code}]: ${ex.status.description}")
            } else {
                logger.error("SubmitTelemetry ERROR: ${ex.message}", ex)
            }
        }
    }

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
