#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

echo "=== Generating mTLS Certificates ==="

# 1. Create CA Key and Certificate
echo "--> Generating Root CA..."
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout ca.key -out ca.crt \
  -subj "/C=US/ST=State/L=City/O=SignalCorp/OU=Security/CN=SignalSystem-RootCA"

# Function to generate signed cert with optional SAN
generate_cert() {
  local NAME=$1
  local CN=$2
  local SAN=$3

  echo "--> Generating certificate for ${NAME} (CN=${CN})..."
  openssl req -newkey rsa:2048 -nodes \
    -keyout "${NAME}.key" -out "${NAME}.csr" \
    -subj "/C=US/ST=State/L=City/O=SignalCorp/OU=Services/CN=${CN}"

  if [ -n "$SAN" ]; then
    openssl x509 -req -days 365 -in "${NAME}.csr" \
      -CA ca.crt -CAkey ca.key -CAcreateserial \
      -out "${NAME}.crt" \
      -extfile <(printf "subjectAltName=%s" "$SAN")
  else
    openssl x509 -req -days 365 -in "${NAME}.csr" \
      -CA ca.crt -CAkey ca.key -CAcreateserial \
      -out "${NAME}.crt"
  fi

  # Convert private key to PKCS8 format if needed by Netty / gRPC Java
  openssl pkcs8 -topk8 -nocrypt -in "${NAME}.key" -out "${NAME}.pem"

  rm -f "${NAME}.csr"
}

# 2. Server Cert (with SAN localhost, 127.0.0.1)
generate_cert "server" "localhost" "DNS:localhost,IP:127.0.0.1"

# 3. Client Certs
generate_cert "alpha-client" "alpha-client" ""
generate_cert "beta-client" "beta-client" ""
generate_cert "unauthorized-client" "unauthorized-client" ""

echo "=== Certificates generated successfully in $DIR ==="
ls -la *.crt *.pem
