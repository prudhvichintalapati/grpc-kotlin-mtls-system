# Signal gRPC System (Akka gRPC, Pure mTLS & Auto-Reconnecting Streams)

A multi-module Kotlin & Gradle gRPC application powered by **Akka gRPC** (Akka Streams + Akka HTTP) demonstrating:
- **Akka HTTP Server (HTTP/2)** serving gRPC requests via Akka Streams.
- **Mutual TLS (mTLS) Authentication Only**: Strict cryptographic verification at the transport layer via `SSLEngine.needClientAuth = true` without policy or RBAC restrictions.
- **Bi-Directional Event-Driven Streaming** (`EventChannel`) using Akka Streams `Source`.
- **Resilient Auto-Reconnection** with exponential backoff on stream network disconnects.

---

## 🏗️ Architecture Comparison: Netty gRPC vs Akka gRPC

| Dimension | `mtls-only-sample` (gRPC-Kotlin / Netty) | `akka-grpc-mtls-sample` (Akka gRPC) |
| :--- | :--- | :--- |
| **Server Engine** | `io.grpc.netty.shaded.NettyServerBuilder` | **Akka HTTP (`Http.newServerAt`)** over HTTP/2 |
| **Reactive Model** | Kotlin Coroutines `Flow<T>` | **Akka Streams `Source<T, NotUsed>`** |
| **mTLS Enforcement** | Netty `GrpcSslContexts` + `ClientAuth.REQUIRE` | Java `SSLEngine.needClientAuth = true` via `HttpsConnectionContext` |
| **Service Contract** | `SignalServiceCoroutineImplBase` | `SignalService` interface (`CompletionStage` / `Source`) |
| **Request Dispatch** | gRPC Netty Server Interceptors | `SignalServiceHandlerFactory` creating Akka HTTP handler |
| **Access Policy** | **None** (Pure mTLS) | **None** (Pure mTLS) |

---

## 🔐 How Pure mTLS Works in Akka HTTP

In this sample, authentication is enforced at the TLS transport layer:

```kotlin
val httpsContext = ConnectionContext.httpsServer {
    val engine = sslContext.createSSLEngine()
    engine.useClientMode = false
    engine.needClientAuth = true // ENFORCE mTLS: Demands client cert signed by trusted Root CA
    engine
}

Http.get(actorSystem)
    .newServerAt("0.0.0.0", 8443)
    .enableHttps(httpsContext)
    .bind(handler)
```

1. **Valid Clients (`alpha-client`, `beta-client`)**: Present X.509 certificates signed by the trusted Root CA (`ca.crt`). The TLS handshake succeeds, and the client can invoke all gRPC methods without restrictions.
2. **Untrusted Clients (`untrusted-client`)**: Present certificates signed by an untrusted rogue CA. The server's `SSLEngine` fails the TLS handshake immediately (`SSLHandshakeException`), terminating the TCP socket before any HTTP/2 frames reach Akka HTTP.

---

## ⚡ Akka Streams Bi-directional Streaming & Auto-Reconnect

### How `EventChannel` Works
- **Client to Server Stream**: Client emits `EventMessage` payloads with incrementing sequence numbers.
- **Server to Client Stream**: Server processes events reactively via Akka Streams `Source` and emits `EventResponse` ACKs.
- **Connection Resilience**:
  - Netty Keepalive PINGs detect broken TCP connections immediately.
  - On network drop or server restart, the client logs a warning, applies **exponential backoff (1s ➔ 2s ➔ 4s ➔ max 10s)**, rebuilds the channel, and resumes streaming.

---

## 📋 Prerequisites

- **Java JDK**: 17 or higher
- **OpenSSL**: Installed on your system (for certificate generation)
- **Gradle**: System `gradle` or `./gradlew`

---

## 🚀 Step-by-Step Manual Testing Guide

### Step 1: Generate mTLS Certificates

Run the certificate generation script:

```bash
chmod +x certs/generate-certs.sh
./certs/generate-certs.sh
```

| File | Purpose | Subject CN | Signer |
| :--- | :--- | :--- | :--- |
| `ca.crt` / `ca.key` | Trusted Root CA | `SignalSystem-RootCA` | Self-signed |
| `server.crt` / `server.pem` | Server Certificate & Key | `localhost` (SAN: `127.0.0.1`) | `SignalSystem-RootCA` |
| `alpha-client.crt` / `alpha-client.pem` | Valid Client Certificate | `alpha-client` | `SignalSystem-RootCA` |
| `beta-client.crt` / `beta-client.pem` | Valid Client Certificate | `beta-client` | `SignalSystem-RootCA` |
| `untrusted-client.crt` / `untrusted-client.pem` | Untrusted Client Certificate | `untrusted-client` | `Rogue-RootCA` |

---

### Step 2: Start the Akka gRPC Server

In Terminal 1:

```bash
gradle :server:run
```

Expected startup logs:
```text
INFO com.example.server.AkkaMtlsServer -- ==================================================
INFO com.example.server.AkkaMtlsServer -- Akka gRPC Server (mTLS-Only) started on port 8443
INFO com.example.server.AkkaMtlsServer -- mTLS Enforced: YES (SSLEngine.needClientAuth = true, Trusting CA: ca.crt)
INFO com.example.server.AkkaMtlsServer -- Engine: Akka HTTP (HTTP/2) + Akka Streams
INFO com.example.server.AkkaMtlsServer -- Access Control: Pure mTLS Authentication (No Policy Rules)
INFO com.example.server.AkkaMtlsServer -- ==================================================
```

---

### Step 3: Run the Automated Client Demo Suite

In Terminal 2:

```bash
gradle :client:run
```

This executes:
1. **`alpha-client`**: Calls `GetHealth`, `SendSignal`, `SubmitTelemetry`, and streams events over Akka Streams. (All **ALLOWED**).
2. **`beta-client`**: Calls `GetHealth`, `SendSignal`, `SubmitTelemetry`, and streams events. (All **ALLOWED** via pure mTLS).
3. **`untrusted-client`**: Attempts to connect using a certificate signed by an untrusted rogue CA. Connection is **REJECTED at the TLS handshake layer** (`SSLHandshakeException`).

---

### Step 4: Run the Auto-Reconnecting Stream Demo

In Terminal 2:

```bash
gradle :client:run --args="stream"
```

Live event streaming:

```text
INFO ClientMain -- STARTING LIVE AKKA gRPC STREAM AUTO-RECONNECT DEMO
INFO AkkaSignalClient -- --> [STREAM OUT] Sending Event (Seq #1)
INFO AkkaSignalClient -- <-- [STREAM IN] Server ACK: [ACK] Event 'EVT-...' (Seq #1) acknowledged by Akka gRPC server
INFO AkkaSignalClient -- --> [STREAM OUT] Sending Event (Seq #2)
INFO AkkaSignalClient -- <-- [STREAM IN] Server ACK: [ACK] Event 'EVT-...' (Seq #2) acknowledged by Akka gRPC server
```

#### Test Live Auto-Reconnection under Server Restarts:
1. Stop the server in Terminal 1 (`Ctrl+C`).
2. Client logs in Terminal 2:
   ```text
   WARN AkkaSignalClient -- ⚠️ Stream connection lost (UNAVAILABLE). Reconnecting in 1000ms...
   ```
3. Restart the server in Terminal 1 (`gradle :server:run`).
4. Client automatically reconnects over mTLS and continues streaming without terminating!

---

### Step 5: Automated One-Command Demo

To run everything (cert generation check, server boot, client test suite, log output, and clean shutdown):

```bash
chmod +x run-demo.sh
./run-demo.sh
```
