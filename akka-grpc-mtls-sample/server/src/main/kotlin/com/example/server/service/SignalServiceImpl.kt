package com.example.server.service

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.javadsl.Source
import com.example.signal.v1.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class SignalServiceImpl(private val system: ActorSystem) : SignalService {

    companion object {
        private val logger = LoggerFactory.getLogger(SignalServiceImpl::class.java)
        private val startTime = Instant.now()
    }

    override fun sendSignal(inReq: SignalRequest): CompletionStage<SignalResponse> {
        logger.info(
            "Processing SendSignal over Akka gRPC (mTLS): id={}, type={}, payload='{}'",
            inReq.signalId, inReq.signalType, inReq.payload
        )

        val response = SignalResponse.newBuilder()
            .setSignalId(inReq.signalId)
            .setSuccess(true)
            .setMessage("Signal '${inReq.signalId}' processed successfully by Akka gRPC server")
            .setProcessedAt(Instant.now().toEpochMilli())
            .setStatusCode("200_OK")
            .build()

        return CompletableFuture.completedFuture(response)
    }

    override fun getHealth(inReq: HealthCheckRequest): CompletionStage<HealthCheckResponse> {
        logger.info("Processing GetHealth over Akka gRPC (mTLS): client='{}'", inReq.clientName)

        val uptimeSeconds = Instant.now().epochSecond - startTime.epochSecond

        val response = HealthCheckResponse.newBuilder()
            .setStatus("UP")
            .setUptimeSeconds(uptimeSeconds)
            .setServerTime(Instant.now().toString())
            .build()

        return CompletableFuture.completedFuture(response)
    }

    override fun submitTelemetry(inReq: TelemetryRequest): CompletionStage<TelemetryResponse> {
        logger.info("Processing SubmitTelemetry over Akka gRPC (mTLS): metrics length={}", inReq.metricsJson.length)

        val response = TelemetryResponse.newBuilder()
            .setItemsAccepted(1)
            .build()

        return CompletableFuture.completedFuture(response)
    }

    /**
     * Bi-directional Event-Driven Streaming RPC implemented via Akka Streams Source.
     */
    override fun eventChannel(inSource: Source<EventMessage, NotUsed>): Source<EventResponse, NotUsed> {
        logger.info("Client opened EventChannel stream over Akka Streams gRPC (mTLS)")

        return inSource
            .map { event ->
                logger.info(
                    "Received EventMessage via Akka Stream: seq={}, id='{}', type='{}', payload='{}'",
                    event.sequenceNumber, event.eventId, event.eventType, event.payload
                )

                EventResponse.newBuilder()
                    .setEventId(event.eventId)
                    .setStatus("ACK")
                    .setMessage("Event '${event.eventId}' (Seq #${event.sequenceNumber}) acknowledged by Akka gRPC server")
                    .setSequenceNumber(event.sequenceNumber)
                    .setProcessedAt(Instant.now().toEpochMilli())
                    .build()
            }
    }
}
