package com.example.signal.v1;

import akka.NotUsed;
import akka.stream.javadsl.Source;
import java.util.concurrent.CompletionStage;

/**
 * Akka gRPC Service Interface for SignalService.
 * Maps Unary RPCs to CompletionStage and Streaming RPCs to Akka Streams Source.
 */
public interface SignalService {

    CompletionStage<SignalResponse> sendSignal(SignalRequest in);

    CompletionStage<HealthCheckResponse> getHealth(HealthCheckRequest in);

    CompletionStage<TelemetryResponse> submitTelemetry(TelemetryRequest in);

    Source<EventResponse, NotUsed> eventChannel(Source<EventMessage, NotUsed> in);
}
