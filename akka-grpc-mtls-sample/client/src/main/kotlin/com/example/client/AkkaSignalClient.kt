package com.example.client

import com.example.signal.v1.*
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext

class AkkaSignalClient(
    private val host: String,
    private val port: Int,
    private val clientCertFile: File,
    private val clientKeyFile: File,
    private val caCertFile: File
) : Closeable {

    companion object {
        private val logger = LoggerFactory.getLogger(AkkaSignalClient::class.java)
    }

    private var channel: ManagedChannel
    private var blockingStub: SignalServiceGrpc.SignalServiceBlockingStub
    private var asyncStub: SignalServiceGrpc.SignalServiceStub

    init {
        check(clientCertFile.exists()) { "Client cert not found: ${clientCertFile.absolutePath}" }
        check(clientKeyFile.exists()) { "Client key not found: ${clientKeyFile.absolutePath}" }
        check(caCertFile.exists()) { "CA cert not found: ${caCertFile.absolutePath}" }

        channel = buildChannel()
        blockingStub = SignalServiceGrpc.newBlockingStub(channel)
        asyncStub = SignalServiceGrpc.newStub(channel)
    }

    private fun buildChannel(): ManagedChannel {
        val sslContext = GrpcSslContexts.forClient()
            .keyManager(clientCertFile, clientKeyFile)
            .trustManager(caCertFile)
            .build()

        return NettyChannelBuilder.forAddress(host, port)
            .sslContext(sslContext)
            .overrideAuthority("localhost")
            .keepAliveTime(10, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    fun sendSignal(id: String, type: String, payload: String): Result<SignalResponse> {
        val request = SignalRequest.newBuilder()
            .setSignalId(id)
            .setSignalType(type)
            .setPayload(payload)
            .setTimestamp(System.currentTimeMillis())
            .build()

        return runCatching {
            blockingStub.sendSignal(request)
        }.onSuccess { resp ->
            logger.info("SendSignal SUCCESS: [{}] {}", resp.statusCode, resp.message)
        }.onFailure { ex ->
            if (ex is StatusRuntimeException) {
                logger.warn("SendSignal FAILED [{}]: {}", ex.status.code, ex.status.description)
            } else {
                logger.error("SendSignal ERROR: {}", ex.message, ex)
            }
        }
    }

    fun getHealth(clientName: String): Result<HealthCheckResponse> {
        val request = HealthCheckRequest.newBuilder()
            .setClientName(clientName)
            .build()

        return runCatching {
            blockingStub.getHealth(request)
        }.onSuccess { resp ->
            logger.info("GetHealth SUCCESS: status={}, uptime={}s, serverTime={}", resp.status, resp.uptimeSeconds, resp.serverTime)
        }.onFailure { ex ->
            if (ex is StatusRuntimeException) {
                logger.warn("GetHealth FAILED [{}]: {}", ex.status.code, ex.status.description)
            } else {
                logger.error("GetHealth ERROR: {}", ex.message, ex)
            }
        }
    }

    fun submitTelemetry(metricsJson: String): Result<TelemetryResponse> {
        val request = TelemetryRequest.newBuilder()
            .setMetricsJson(metricsJson)
            .build()

        return runCatching {
            blockingStub.submitTelemetry(request)
        }.onSuccess { resp ->
            logger.info("SubmitTelemetry SUCCESS: accepted {} items", resp.itemsAccepted)
        }.onFailure { ex ->
            if (ex is StatusRuntimeException) {
                logger.warn("SubmitTelemetry FAILED [{}]: {}", ex.status.code, ex.status.description)
            } else {
                logger.error("SubmitTelemetry ERROR: {}", ex.message, ex)
            }
        }
    }

    /**
     * Resilient Auto-Reconnecting Bi-directional Event Stream with Exponential Backoff over mTLS.
     */
    fun startAutoReconnectingEventStream(
        maxEvents: Int = 10,
        delayBetweenEventsMs: Long = 1000L,
        onResponse: (EventResponse) -> Unit = {}
    ) = runBlocking {
        val sequenceCounter = AtomicLong(1)
        var attempt = 0

        while (maxEvents < 0 || sequenceCounter.get() <= maxEvents) {
            attempt++
            logger.info("Establishing Akka gRPC EventChannel Stream (Connection Attempt #{}) over mTLS...", attempt)

            val completed = CountDownLatch(1)
            val streamFailed = AtomicBoolean(false)
            var failureReason = ""

            val responseObserver = object : StreamObserver<EventResponse> {
                override fun onNext(response: EventResponse) {
                    attempt = 0 // Reset attempt counter upon successful communication
                    logger.info("<-- [STREAM IN] Server ACK: [{}] {}", response.status, response.message)
                    onResponse(response)
                }

                override fun onError(t: Throwable) {
                    streamFailed.set(true)
                    failureReason = t.message ?: "Unknown error"
                    completed.countDown()
                }

                override fun onCompleted() {
                    logger.info("EventChannel server stream completed.")
                    completed.countDown()
                }
            }

            val requestObserver = asyncStub.eventChannel(responseObserver)

            try {
                while ((maxEvents < 0 || sequenceCounter.get() <= maxEvents) && !streamFailed.get()) {
                    val seq = sequenceCounter.get()
                    val event = EventMessage.newBuilder()
                        .setEventId("EVT-${System.currentTimeMillis()}")
                        .setEventType("TELEMETRY_EVENT")
                        .setPayload("Live event data (seq #$seq)")
                        .setSequenceNumber(seq)
                        .setTimestamp(System.currentTimeMillis())
                        .build()

                    logger.info("--> [STREAM OUT] Sending Event (Seq #{})", seq)
                    requestObserver.onNext(event)
                    sequenceCounter.incrementAndGet()
                    delay(delayBetweenEventsMs)
                }
                requestObserver.onCompleted()
                completed.await(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                streamFailed.set(true)
                failureReason = e.message ?: "Stream writing error"
            }

            if (streamFailed.get() && (maxEvents < 0 || sequenceCounter.get() <= maxEvents)) {
                val safeAttempt = attempt.coerceIn(1, 10)
                val backoffMs = (1000L * (1 shl (safeAttempt - 1))).coerceAtMost(10000L)
                logger.warn("⚠️ Stream connection lost ({}). Reconnecting in {}ms...", failureReason, backoffMs)
                rebuildChannelIfNeeded()
                delay(backoffMs)
            }
        }

        logger.info("EventChannel streaming task finished.")
    }

    private fun rebuildChannelIfNeeded() {
        try {
            if (channel.isShutdown || channel.isTerminated) {
                channel = buildChannel()
                blockingStub = SignalServiceGrpc.newBlockingStub(channel)
                asyncStub = SignalServiceGrpc.newStub(channel)
            }
        } catch (e: Exception) {
            logger.error("Failed to rebuild client channel: {}", e.message)
        }
    }

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
