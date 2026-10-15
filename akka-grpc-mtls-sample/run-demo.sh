#!/usr/bin/env bash
set -e

export JAVA_HOME="/Users/prudhvirajuchintalapati/Library/Java/JavaVirtualMachines/corretto-17.0.10/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

echo "=== Ensuring mTLS Certificates exist for Akka gRPC ==="
if [ ! -f "certs/server.crt" ]; then
    chmod +x certs/generate-certs.sh
    ./certs/generate-certs.sh
fi

echo "=== Starting Akka gRPC Server with mTLS (HTTP/2) ==="
gradle :server:run > server.log 2>&1 &
SERVER_PID=$!

echo "Server started with PID: $SERVER_PID. Waiting for Akka gRPC server on port 8443..."
sleep 6

cleanup() {
    echo "=== Cleaning up Server (PID: $SERVER_PID) ==="
    kill -9 $SERVER_PID 2>/dev/null || true
    pkill -f "com.example.server.ServerMainKt" 2>/dev/null || true
}
trap cleanup EXIT

echo "=== Running Client Pure mTLS Verification Suite ==="
gradle :client:run

echo ""
echo "=== Server Log Highlights ==="
cat server.log | grep -E "AkkaMtlsServer|SignalServiceImpl|Akka" || cat server.log
