# Signal gRPC System (mTLS, Policy-Based RBAC & Auto-Reconnecting Streams)

A multi-module Kotlin & Gradle gRPC application demonstrating:
- **Mutual TLS (mTLS)** client & server authentication
- **Role-Based Access Control (RBAC)** via server-side `policy.json`
- **Bi-Directional Event-Driven gRPC Streaming** (`EventChannel`)
- **Resilient Auto-Reconnection** with exponential backoff on stream network disconnects

---

## 🏗️ Architecture & Subprojects

The system consists of 3 Gradle subprojects:

1. **`api`**: Contains the Protobuf definition (`signal_service.proto`) defining Unary RPCs and the `EventChannel` bi-directional streaming RPC.
2. **`server`**: Netty gRPC server enforcing mTLS authentication, checking client certificate Common Name (`CN`) against `policy.json` via a `ServerInterceptor`, and processing streaming events.
3. **`client`**: Netty gRPC client with built-in **Auto-Reconnection** loop and exponential backoff to handle network drops seamlessly.

---

## ⚡ Event-Driven Streaming & Auto-Reconnection

### How `EventChannel` Works
- **Client to Server Stream**: Client continuously emits `EventMessage` payloads (with sequence numbers).
- **Server to Client Stream**: Server processes events asynchronously and emits `EventResponse` (ACKs) over the same long-lived HTTP/2 stream.
- **Connection Resilience (Auto-Reconnect)**:
  - The client wraps stream collection in a coroutine loop.
  - Netty Keepalive PINGs (`keepAliveTime(10, SECONDS)`) detect broken TCP connections immediately.
  - On network drop or server restart (`StatusRuntimeException`), the client logs a warning, waits using **exponential backoff (1s ➔ 2s ➔ 4s ➔ max 10s)**, rebuilds the gRPC channel, and resumes streaming event sequence numbers without crashing.

---

## 📋 Prerequisites

- **Java JDK**: 17 or higher
- **OpenSSL**: Installed on your system (for certificate generation)
- **Gradle**: System `gradle` or `./gradlew` wrapper

---

## 🚀 Step-by-Step Manual Testing Guide

### Step 1: Generate mTLS Certificates

Run the certificate generation script:

```bash
chmod +x certs/generate-certs.sh
./certs/generate-certs.sh
```

| File | Purpose | Subject CN |
| :--- | :--- | :--- |
| `ca.crt` / `ca.key` | Root Certificate Authority | `SignalSystem-RootCA` |
| `server.crt` / `server.pem` | Server Certificate & Key | `localhost` (SAN: `127.0.0.1`) |
| `alpha-client.crt` / `alpha-client.pem` | Full Access Client Certificate | `alpha-client` |
| `beta-client.crt` / `beta-client.pem` | Stream & Health Access Certificate | `beta-client` |
| `unauthorized-client.crt` / `unauthorized-client.pem` | Unauthorized Client Certificate | `unauthorized-client` |

---

### Step 2: Start the gRPC Server

In Terminal 1:

```bash
gradle :server:run
```

```text
INFO com.example.server.MtlsServer -- Signal gRPC Server started successfully on port 8443
INFO com.example.server.MtlsServer -- mTLS Enforced: YES (Trusting CA: ca.crt)
INFO com.example.server.MtlsServer -- Policy Loaded: .../server/src/main/resources/policy.json
```

---

### Step 3: Run the Auto-Reconnecting Stream Demo

In Terminal 2, start the dedicated streaming mode:

```bash
gradle :client:run --args="stream"
```

You will see live bi-directional event streaming:

```text
INFO ClientMain -- STARTING LIVE gRPC STREAM AUTO-RECONNECT DEMO
INFO SignalClient -- --> [STREAM OUT] Sending Event (Seq #1)
INFO SignalClient -- <-- [STREAM IN] Server Response: [ACK] Event 'EVT-1790822' (Seq #1) acknowledged...
INFO SignalClient -- --> [STREAM OUT] Sending Event (Seq #2)
INFO SignalClient -- <-- [STREAM IN] Server Response: [ACK] Event 'EVT-1790822' (Seq #2) acknowledged...
```

#### ⚡ Test Automatic Reconnection under Network Loss:
1. While `gradle :client:run --args="stream"` is running in Terminal 2, **kill or stop the server** in Terminal 1 (`Ctrl+C`).
2. Notice the client output in Terminal 2:
   ```text
   WARN SignalClient -- ⚠️ Stream connection lost ([UNAVAILABLE] Transport closed). Reconnecting in 1000ms...
   WARN SignalClient -- ⚠️ Stream connection lost ([UNAVAILABLE] io exception). Reconnecting in 2000ms...
   ```
3. **Restart the server** in Terminal 1 (`gradle :server:run`).
4. Watch the client in Terminal 2 automatically re-establish the mTLS stream and resume sending events without manual intervention!

---

### Step 4: Run the Full Test Matrix

```bash
gradle :client:run
```

Runs tests for all client identities (`alpha-client`, `beta-client`, `unauthorized-client`) against unary and streaming RPC endpoints.

---

## 📊 Authorization Permission Matrix

| Client Certificate (CN) | `GetHealth` | `SendSignal` | `SubmitTelemetry` | `EventChannel` (Stream) |
| :--- | :---: | :---: | :---: | :---: |
| **`alpha-client`** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **`beta-client`** | ✅ Allowed | ❌ Denied | ❌ Denied | ✅ Allowed |
| **`unauthorized-client`** | ❌ Denied | ❌ Denied | ❌ Denied | ❌ Denied |

---

## 🛠️ Automated All-In-One Script

```bash
./run-demo.sh
```
