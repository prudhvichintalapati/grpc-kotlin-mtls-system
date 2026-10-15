# Signal gRPC System (Pure mTLS & Auto-Reconnecting Streams)

A multi-module Kotlin & Gradle gRPC application demonstrating:
- **Mutual TLS (mTLS) Authentication Only**: Strict cryptographic verification at the transport layer without policy or RBAC restrictions.
- **Bi-Directional Event-Driven gRPC Streaming** (`EventChannel`).
- **Resilient Auto-Reconnection** with exponential backoff on stream network disconnects.

---

## 🆚 Pure mTLS vs Policy-Based Access Control

| Feature | First Project (Policy + mTLS) | This Sample (`mtls-only-sample`) |
| :--- | :--- | :--- |
| **Authentication** | mTLS client & server certificate verification | mTLS client & server certificate verification |
| **Authorization** | Method-level RBAC via `policy.json` | **None** — all CA-authenticated clients have full access |
| **Access Decision** | `PolicyEvaluator` matches client CN & method name | Mutual TLS handshake passing certifies caller |
| **Dependencies** | Requires Jackson JSON parser for policy rules | Lean — no JSON parser or policy engine |
| **Unauthorized Action** | Returns `PERMISSION_DENIED` (status code 7) | Untrusted clients are **blocked during TLS handshake** |

---

## 🏗️ Architecture & Subprojects

The project consists of 3 Gradle subprojects:

1. **`api`**: Contains Protobuf definition (`signal_service.proto`) defining Unary RPCs (`SendSignal`, `GetHealth`, `SubmitTelemetry`) and the `EventChannel` bi-directional streaming RPC.
2. **`server`**: Netty gRPC server enforcing mTLS (`ClientAuth.REQUIRE`) against a trusted Root CA (`ca.crt`). It extracts the client's Subject Common Name (`CN`) for audit and logging, but applies no restrictive policy.
3. **`client`**: Netty gRPC client with built-in **Auto-Reconnection** loop and exponential backoff to handle network drops seamlessly.

---

## ⚡ Event-Driven Streaming & Auto-Reconnection

### How `EventChannel` Works
- **Client to Server Stream**: Client continuously emits `EventMessage` payloads (with sequence numbers) over an mTLS-secured HTTP/2 connection.
- **Server to Client Stream**: Server processes events asynchronously and emits `EventResponse` (ACKs) over the same stream.
- **Connection Resilience (Auto-Reconnect)**:
  - The client wraps stream collection in a coroutine loop.
  - Netty Keepalive PINGs (`keepAliveTime(10, SECONDS)`) detect broken TCP connections immediately.
  - On network drop or server restart (`StatusRuntimeException`), the client logs a warning, waits using **exponential backoff (1s ➔ 2s ➔ 4s ➔ max 10s)**, rebuilds the gRPC channel, and resumes streaming without crashing.

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

### Step 2: Start the gRPC Server

In Terminal 1:

```bash
gradle :server:run
```

Expected startup logs:
```text
INFO com.example.server.MtlsServer -- ==================================================
INFO com.example.server.MtlsServer -- Signal gRPC Server (mTLS-Only) started on port 8443
INFO com.example.server.MtlsServer -- mTLS Enforced: YES (Trusting CA: ca.crt)
INFO com.example.server.MtlsServer -- Access Control: Pure mTLS Authentication (No Policy Rules)
INFO com.example.server.MtlsServer -- ==================================================
```

---

### Step 3: Run the Automated Client Demo Suite

In Terminal 2:

```bash
gradle :client:run
```

This demo executes:
1. **`alpha-client`**: Calls `GetHealth`, `SendSignal`, `SubmitTelemetry`, and streams events. (All **ALLOWED**).
2. **`beta-client`**: Calls `GetHealth`, `SendSignal`, `SubmitTelemetry`, and streams events. (All **ALLOWED** — in contrast to the policy sample where `SendSignal` was rejected).
3. **`untrusted-client`**: Attempts to connect using a certificate signed by an untrusted rogue CA. The connection is **REJECTED at the TLS handshake layer** (`SSLHandshakeException`).

---

### Step 4: Run the Auto-Reconnecting Stream Demo

In Terminal 2:

```bash
gradle :client:run --args="stream"
```

Live bi-directional event streaming:

```text
INFO ClientMain -- STARTING LIVE gRPC STREAM AUTO-RECONNECT DEMO (mTLS-Only)
INFO SignalClient -- --> [STREAM OUT] Sending Event (Seq #1)
INFO SignalClient -- <-- [STREAM IN] Server Response: [ACK] Event 'EVT-...' (Seq #1) acknowledged by server for mTLS client 'alpha-client'
INFO SignalClient -- --> [STREAM OUT] Sending Event (Seq #2)
INFO SignalClient -- <-- [STREAM IN] Server Response: [ACK] Event 'EVT-...' (Seq #2) acknowledged by server for mTLS client 'alpha-client'
```

#### Test Live Auto-Reconnection under Server Restarts:
1. Stop the server in Terminal 1 (`Ctrl+C`).
2. Client logs in Terminal 2:
   ```text
   WARN SignalClient -- ⚠️ Stream connection lost ([UNAVAILABLE] Transport closed). Reconnecting in 1000ms...
   ```
3. Restart the server in Terminal 1 (`gradle :server:run`).
4. Client automatically reconnects over mTLS and continues streaming without terminating!

---

### Step 5: Automated One-Command Demo

To run everything (cert check, server boot, client test suite, log output, and clean shutdown):

```bash
chmod +x run-demo.sh
./run-demo.sh
```
