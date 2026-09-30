#!/usr/bin/env bash
set -e

export JAVA_HOME="/Users/prudhvirajuchintalapati/Library/Java/JavaVirtualMachines/corretto-17.0.10/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

echo "=== Ensuring mTLS Certificates exist ==="
if [ ! -f "certs/server.crt" ]; then
    ./certs/generate-certs.sh
fi

echo "=== Starting gRPC Server with mTLS and Policy Enforcement ==="
gradle :server:run > server.log 2>&1 &
SERVER_PID=$!

echo "Server started with PID: $SERVER_PID. Waiting for gRPC server on port 8443..."
sleep 5

cleanup() {
    echo "=== Cleaning up Server (PID: $SERVER_PID) ==="
    kill -9 $SERVER_PID 2>/dev/null || true
    pkill -f "com.example.server.ServerMainKt" 2>/dev/null || true
}
trap cleanup EXIT

echo "=== Running gRPC Client RBAC Policy Verification Suite ==="
gradle :client:run

echo ""
echo "=== Server Log Highlights ==="
cat server.log | grep -E "MtlsServer|MtlsAuthInterceptor|SignalServiceImpl" || cat server.log
