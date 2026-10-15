package com.example.signal.v1;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.RawHeader;
import akka.japi.function.Function;
import akka.stream.Materializer;
import akka.stream.javadsl.Framing;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import org.slf4j.LoggerFactory;

import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class SignalServiceHandlerFactory {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(SignalServiceHandlerFactory.class);
    private static final ContentType.Binary GRPC_CONTENT_TYPE = ContentTypes.APPLICATION_GRPC_PROTO;

    public static Function<HttpRequest, CompletionStage<HttpResponse>> create(
            SignalService service,
            ActorSystem system
    ) {
        Materializer mat = Materializer.createMaterializer(system);

        return request -> {
            String path = request.getUri().path();

            switch (path) {
                case "/com.example.signal.v1.SignalService/SendSignal":
                    return handleSendSignal(request, service, mat);

                case "/com.example.signal.v1.SignalService/GetHealth":
                    return handleGetHealth(request, service, mat);

                case "/com.example.signal.v1.SignalService/SubmitTelemetry":
                    return handleSubmitTelemetry(request, service, mat);

                case "/com.example.signal.v1.SignalService/EventChannel":
                    return handleEventChannel(request, service, mat);

                default:
                    logger.warn("gRPC Method not found: {}", path);
                    return CompletableFuture.completedFuture(
                            HttpResponse.create()
                                    .withStatus(StatusCodes.NOT_FOUND)
                                    .addHeader(RawHeader.create("grpc-status", "12")) // UNIMPLEMENTED
                    );
            }
        };
    }

    private static CompletionStage<HttpResponse> handleSendSignal(
            HttpRequest request,
            SignalService service,
            Materializer mat
    ) {
        return request.entity().toStrict(5000, mat)
                .thenCompose(strict -> {
                    try {
                        byte[] rawBytes = extractGrpcPayload(strict.getData().toArray());
                        SignalRequest req = SignalRequest.parseFrom(rawBytes);
                        return service.sendSignal(req)
                                .thenApply(resp -> createGrpcResponse(resp.toByteArray()));
                    } catch (Exception e) {
                        logger.error("Error processing SendSignal: {}", e.getMessage(), e);
                        return CompletableFuture.completedFuture(createGrpcErrorResponse(13, e.getMessage()));
                    }
                });
    }

    private static CompletionStage<HttpResponse> handleGetHealth(
            HttpRequest request,
            SignalService service,
            Materializer mat
    ) {
        return request.entity().toStrict(5000, mat)
                .thenCompose(strict -> {
                    try {
                        byte[] rawBytes = extractGrpcPayload(strict.getData().toArray());
                        HealthCheckRequest req = HealthCheckRequest.parseFrom(rawBytes);
                        return service.getHealth(req)
                                .thenApply(resp -> createGrpcResponse(resp.toByteArray()));
                    } catch (Exception e) {
                        logger.error("Error processing GetHealth: {}", e.getMessage(), e);
                        return CompletableFuture.completedFuture(createGrpcErrorResponse(13, e.getMessage()));
                    }
                });
    }

    private static CompletionStage<HttpResponse> handleSubmitTelemetry(
            HttpRequest request,
            SignalService service,
            Materializer mat
    ) {
        return request.entity().toStrict(5000, mat)
                .thenCompose(strict -> {
                    try {
                        byte[] rawBytes = extractGrpcPayload(strict.getData().toArray());
                        TelemetryRequest req = TelemetryRequest.parseFrom(rawBytes);
                        return service.submitTelemetry(req)
                                .thenApply(resp -> createGrpcResponse(resp.toByteArray()));
                    } catch (Exception e) {
                        logger.error("Error processing SubmitTelemetry: {}", e.getMessage(), e);
                        return CompletableFuture.completedFuture(createGrpcErrorResponse(13, e.getMessage()));
                    }
                });
    }

    private static CompletionStage<HttpResponse> handleEventChannel(
            HttpRequest request,
            SignalService service,
            Materializer mat
    ) {
        // Stream of incoming bytes decoded into gRPC frames of EventMessage
        Source<EventMessage, NotUsed> incomingEvents = request.entity().getDataBytes()
                .via(Framing.lengthField(4, 1, 4 * 1024 * 1024, ByteOrder.BIG_ENDIAN))
                .map(frame -> {
                    byte[] payload = extractGrpcPayload(frame.toArray());
                    return EventMessage.parseFrom(payload);
                })
                .mapMaterializedValue(ignore -> NotUsed.getInstance());

        // Delegate to service stream
        Source<EventResponse, NotUsed> responseStream = service.eventChannel(incomingEvents);

        // Serialize EventResponse objects into gRPC byte frames
        Source<ByteString, NotUsed> byteStringStream = responseStream
                .map(resp -> ByteString.fromArray(encodeGrpcFrame(resp.toByteArray())));

        HttpResponse response = HttpResponse.create()
                .withStatus(StatusCodes.OK)
                .withEntity(HttpEntities.createChunked(GRPC_CONTENT_TYPE, byteStringStream))
                .addHeader(RawHeader.create("grpc-status", "0"))
                .addHeader(RawHeader.create("grpc-message", "OK"));

        return CompletableFuture.completedFuture(response);
    }

    private static byte[] extractGrpcPayload(byte[] frame) {
        if (frame.length < 5) return new byte[0];
        byte[] payload = new byte[frame.length - 5];
        System.arraycopy(frame, 5, payload, 0, payload.length);
        return payload;
    }

    private static byte[] encodeGrpcFrame(byte[] messageBytes) {
        byte[] frame = new byte[5 + messageBytes.length];
        frame[0] = 0; // Uncompressed flag
        frame[1] = (byte) ((messageBytes.length >> 24) & 0xFF);
        frame[2] = (byte) ((messageBytes.length >> 16) & 0xFF);
        frame[3] = (byte) ((messageBytes.length >> 8) & 0xFF);
        frame[4] = (byte) (messageBytes.length & 0xFF);
        System.arraycopy(messageBytes, 0, frame, 5, messageBytes.length);
        return frame;
    }

    private static HttpResponse createGrpcResponse(byte[] messageBytes) {
        byte[] frame = encodeGrpcFrame(messageBytes);
        return HttpResponse.create()
                .withStatus(StatusCodes.OK)
                .withEntity(HttpEntities.create(GRPC_CONTENT_TYPE, frame))
                .addHeader(RawHeader.create("grpc-status", "0"))
                .addHeader(RawHeader.create("grpc-message", "OK"));
    }

    private static HttpResponse createGrpcErrorResponse(int statusCode, String description) {
        return HttpResponse.create()
                .withStatus(StatusCodes.OK)
                .withEntity(HttpEntities.create(GRPC_CONTENT_TYPE, new byte[0]))
                .addHeader(RawHeader.create("grpc-status", String.valueOf(statusCode)))
                .addHeader(RawHeader.create("grpc-message", description != null ? description : "Error"));
    }
}
