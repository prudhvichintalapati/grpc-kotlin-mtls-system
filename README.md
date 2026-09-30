# Signal gRPC System (mTLS & Policy-Based RBAC)

A multi-module Kotlin & Gradle gRPC application demonstrating **Mutual TLS (mTLS)** authentication and **Role-Based Access Control (RBAC)** via a server-side policy file.

---

## 🏗️ Architecture & Subprojects

The system consists of 3 Gradle subprojects:

1. **`api`**: Contains the Protobuf definition (`signal_service.proto`) and auto-generates gRPC Kotlin & Java stubs.
2. **`server`**: Netty gRPC server enforcing mTLS authentication and checking caller certificate identity (`CN`) against `policy.json` via a `ServerInterceptor`.
3. **`client`**: Netty gRPC client configured with mTLS certificates, capable of invoking RPC methods using different client identities.

---

## 📋 Prerequisites

- **Java JDK**: 17 or higher
- **OpenSSL**: Installed on your system (for certificate generation)
- **Gradle**: System `gradle` or `./gradlew` wrapper

---

## 🚀 Step-by-Step Manual Testing Guide

### Step 1: Generate mTLS Certificates

Run the certificate generation script to create the Root CA, Server certificate, and 3 client certificates (`alpha-client`, `beta-client`, and `unauthorized-client`):

```bash
chmod +x certs/generate-certs.sh
./certs/generate-certs.sh
```

This creates the following files in the `certs/` folder:

| File | Purpose | Subject CN |
| :--- | :--- | :--- |
| `ca.crt` / `ca.key` | Root Certificate Authority | `SignalSystem-RootCA` |
| `server.crt` / `server.pem` | Server Certificate & PKCS8 Key | `localhost` (SAN: `127.0.0.1`) |
| `alpha-client.crt` / `alpha-client.pem` | Full Access Client Certificate | `alpha-client` |
| `beta-client.crt` / `beta-client.pem` | Restricted Access Client Certificate | `beta-client` |
| `unauthorized-client.crt` / `unauthorized-client.pem` | Unauthorized Client Certificate | `unauthorized-client` |

---

### Step 2: Start the gRPC Server

Open a terminal window and start the server:

```bash
gradle :server:run
```

When started, you will see output similar to:

```text
INFO com.example.server.MtlsServer -- ==================================================
INFO com.example.server.MtlsServer -- Signal gRPC Server started successfully on port 8443
INFO com.example.server.MtlsServer -- mTLS Enforced: YES (Trusting CA: ca.crt)
INFO com.example.server.MtlsServer -- Policy Loaded: .../server/src/main/resources/policy.json
INFO com.example.server.MtlsServer -- ==================================================
```

> **Note**: Leave this terminal running while performing manual client tests in another terminal window.

---

### Step 3: Run the Client Tests Manually

Open a **second terminal window** in the project directory.

#### Option A: Run the Full Automated Test Matrix
Run the default demo suite which sequentially tests all three client certificate identities:

```bash
gradle :client:run
```

#### Option B: Run for a Specific Client Identity

You can pass the client name (`alpha-client`, `beta-client`, or `unauthorized-client`) as an argument to test individual credentials:

##### 1. Test `alpha-client` (Full Privileges):
```bash
gradle :client:run --args="alpha-client"
```
*Expected Result*: Calls to `GetHealth` and `SendSignal` succeed (`200 OK`).

##### 2. Test `beta-client` (Restricted Privileges):
```bash
gradle :client:run --args="beta-client"
```
*Expected Result*: `GetHealth` succeeds, but `SendSignal` fails with `PERMISSION_DENIED`.

##### 3. Test `unauthorized-client` (No Policy Permissions):
```bash
gradle :client:run --args="unauthorized-client"
```
*Expected Result*: All RPC method calls fail with `PERMISSION_DENIED`.

---

### Step 4: Testing Policy Modifications (Live Rule Changes)

You can modify the policy file on the server to test authorization changes:

1. Open `server/src/main/resources/policy.json`:
   ```json
   {
     "allowedClients": {
       "alpha-client": [
         "com.example.signal.v1.SignalService/SendSignal",
         "com.example.signal.v1.SignalService/GetHealth"
       ],
       "beta-client": [
         "com.example.signal.v1.SignalService/GetHealth",
         "com.example.signal.v1.SignalService/SendSignal"
       ]
     }
   }
   ```
2. Restart the server (`gradle :server:run`).
3. Re-run `gradle :client:run --args="beta-client"`. Notice that `SendSignal` now **succeeds** because `beta-client` was granted permission in `policy.json`.

---

### Step 5: Testing with `grpcurl` (Optional CLI Tool)

If you have `grpcurl` installed, you can test the gRPC server directly from your shell by supplying the client certificate and key:

#### 1. Test `GetHealth` with `alpha-client`:
```bash
grpcurl -cacert certs/ca.crt \
  -cert certs/alpha-client.crt \
  -key certs/alpha-client.pem \
  -d '{"client_name": "cli-test"}' \
  127.0.0.1:8443 com.example.signal.v1.SignalService/GetHealth
```

#### 2. Test `SendSignal` with `beta-client` (Expect Permission Denied):
```bash
grpcurl -cacert certs/ca.crt \
  -cert certs/beta-client.crt \
  -key certs/beta-client.pem \
  -d '{"signal_id": "SIG-99", "signal_type": "WARN", "payload": "Test"}' \
  127.0.0.1:8443 com.example.signal.v1.SignalService/SendSignal
```

---

## 📊 Authorization Permission Matrix

| Client Certificate (CN) | `GetHealth` | `SendSignal` | `SubmitTelemetry` |
| :--- | :---: | :---: | :---: |
| **`alpha-client`** | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **`beta-client`** | ✅ Allowed | ❌ Denied | ❌ Denied |
| **`unauthorized-client`** | ❌ Denied | ❌ Denied | ❌ Denied |
| **No Client Cert (Plain TLS/HTTP)** | ❌ Connection Rejected during TLS Handshake | ❌ Rejected | ❌ Rejected |

---

## 🛠️ Automated End-to-End Demo Script

You can also run the bundled all-in-one demo script which automatically generates certificates, boots the server in the background, executes the client suite, displays server logs, and cleans up:

```bash
./run-demo.sh
```
