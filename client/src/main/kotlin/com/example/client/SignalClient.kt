package com.example.client

import com.example.signal.v1.EventMessage
import com.example.signal.v1.EventResponse
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

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

    private var channel: ManagedChannel
    private var stub: SignalServiceGrpcKt.SignalServiceCoroutineStub

    init {
        check(clientCertFile.exists()) { "Client cert not found: ${clientCertFile.absolutePath}" }
        check(clientKeyFile.exists()) { "Client key not found: ${clientKeyFile.absolutePath}" }
        check(caCertFile.exists()) { "CA cert not found: ${caCertFile.absolutePath}" }

        channel = buildChannel()
        stub = SignalServiceGrpcKt.SignalServiceCoroutineStub(channel)
    }

    private fun buildChannel(): ManagedChannel {
        val sslContext: SslContext = GrpcSslContexts.forClient()
            .keyManager(clientCertFile, clientKeyFile)
            .trustManager(caCertFile)
            .build()

        return NettyChannelBuilder.forAddress(host, port)
            .sslContext(sslContext)
            .overrideAuthority("localhost") // Matches server certificate SAN/CN
            .keepAliveTime(10, TimeUnit.SECONDS) // Send keepalive PINGs every 10s to detect broken TCP
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
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

    /**
     * Starts an event-driven gRPC Stream (EventChannel) with AUTOMATIC RECONNECT & Exponential Backoff.
     * Continuously emits eventMessages, receives asynchronous server ACKs, and automatically reconnects
     * whenever the network connection is broken.
     *
     * @param maxEvents Number of events to send before completing, or -1 for infinite loop.
     * @param delayBetweenEventsMs Delay between sending each event message.
     */
    suspend fun startAutoReconnectingEventStream(
        maxEvents: Int = 10,
        delayBetweenEventsMs: Long = 1000L,
        onResponse: (EventResponse) -> Unit = {}
    ) = coroutineScope {
        val sequenceCounter = AtomicLong(1)
        var attempt = 0

        while (coroutineContext.isActive && (maxEvents < 0 || sequenceCounter.get() <= maxEvents)) {
            attempt++
            logger.info("Establishing gRPC EventChannel Stream (Connection Attempt #${attempt})...")

            val outgoingEventsFlow: Flow<EventMessage> = flow {
                while (coroutineContext.isActive && (maxEvents < 0 || sequenceCounter.get() <= maxEvents)) {
                    val seq = sequenceCounter.get()
                    val event = EventMessage.newBuilder()
                        .setEventId("EVT-${System.currentTimeMillis()}")
                        .setEventType("TELEMETRY_EVENT")
                        .setPayload("Live event data (seq #$seq)")
                        .setSequenceNumber(seq)
                        .setTimestamp(System.currentTimeMillis())
                        .build()

                    logger.info("--> [STREAM OUT] Sending Event (Seq #$seq)")
                    emit(event)
                    sequenceCounter.incrementAndGet()
                    delay(delayBetweenEventsMs)
                }
            }

            try {
                val responseFlow = stub.eventChannel(outgoingEventsFlow)
                
                // Collect server ACKs / EventResponses
                responseFlow.collect { response ->
                    attempt = 0 // Reset attempt count on successful message exchange
                    logger.info("<-- [STREAM IN] Server Response: [{}] {}", response.status, response.message)
                    onResponse(response)
                }
            } catch (ex: Exception) {
                if (!coroutineContext.isActive) break

                val safeAttempt = attempt.coerceIn(1, 10)
                val backoffMs = (1000L * (1 shl (safeAttempt - 1))).coerceAtMost(10000L)
                val reason = if (ex is StatusRuntimeException) "[${ex.status.code}] ${ex.status.description}" else ex.message
                logger.warn("⚠️ Stream connection lost (${reason}). Reconnecting in ${backoffMs}ms...")
                
                // Refresh gRPC channel to rebuild underlying TCP connection if needed
                rebuildStubIfNeeded()
                delay(backoffMs)
            }
        }
        logger.info("EventChannel streaming task finished.")
    }

    private fun rebuildStubIfNeeded() {
        try {
            if (channel.isShutdown || channel.isTerminated) {
                channel = buildChannel()
                stub = SignalServiceGrpcKt.SignalServiceCoroutineStub(channel)
            }
        } catch (e: Exception) {
            logger.error("Failed to rebuild gRPC stub: ${e.message}")
        }
    }

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
