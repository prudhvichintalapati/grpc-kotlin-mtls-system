package com.example.server.service

import com.example.server.auth.AuthContext
import com.example.signal.v1.HealthCheckRequest
import com.example.signal.v1.HealthCheckResponse
import com.example.signal.v1.SignalRequest
import com.example.signal.v1.SignalResponse
import com.example.signal.v1.SignalServiceGrpcKt
import com.example.signal.v1.TelemetryRequest
import com.example.signal.v1.TelemetryResponse
import org.slf4j.LoggerFactory
import java.time.Instant

class SignalServiceImpl : SignalServiceGrpcKt.SignalServiceCoroutineImplBase() {

    companion object {
        private val logger = LoggerFactory.getLogger(SignalServiceImpl::class.java)
        private val startTime = Instant.now()
    }

    override suspend fun sendSignal(request: SignalRequest): SignalResponse {
        val clientCn = AuthContext.CLIENT_CN_KEY.get() ?: "UNKNOWN"
        logger.info(
            "Processing SendSignal from client '{}': id={}, type={}, payload='{}'",
            clientCn, request.signalId, request.signalType, request.payload
        )

        return SignalResponse.newBuilder()
            .setSignalId(request.signalId)
            .setSuccess(true)
            .setMessage("Signal '${request.signalId}' successfully processed for client '$clientCn'")
            .setProcessedAt(Instant.now().toEpochMilli())
            .setStatusCode("200_OK")
            .build()
    }

    override suspend fun getHealth(request: HealthCheckRequest): HealthCheckResponse {
        val clientCn = AuthContext.CLIENT_CN_KEY.get() ?: "UNKNOWN"
        logger.info("Processing GetHealth request from client '{}' (requested client name: '{}')", clientCn, request.clientName)

        val uptimeSeconds = Instant.now().epochSecond - startTime.epochSecond

        return HealthCheckResponse.newBuilder()
            .setStatus("UP")
            .setUptimeSeconds(uptimeSeconds)
            .setServerTime(Instant.now().toString())
            .build()
    }

    override suspend fun submitTelemetry(request: TelemetryRequest): TelemetryResponse {
        val clientCn = AuthContext.CLIENT_CN_KEY.get() ?: "UNKNOWN"
        logger.info("Processing SubmitTelemetry from client '{}': metrics length={}", clientCn, request.metricsJson.length)

        return TelemetryResponse.newBuilder()
            .setItemsAccepted(1)
            .build()
    }
}
