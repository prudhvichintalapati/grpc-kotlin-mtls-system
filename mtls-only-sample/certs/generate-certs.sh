#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

echo "=== Generating mTLS Certificates ==="

# 1. Create Trusted Root CA Key and Certificate
echo "--> Generating Trusted Root CA..."
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout ca.key -out ca.crt \
  -subj "/C=US/ST=State/L=City/O=SignalCorp/OU=Security/CN=SignalSystem-RootCA"

# Helper function to generate signed certificate
generate_cert() {
  local NAME=$1
  local CN=$2
  local CA_NAME=$3
  local SAN=$4

  echo "--> Generating certificate for ${NAME} (CN=${CN}) signed by ${CA_NAME}..."
  openssl req -newkey rsa:2048 -nodes \
    -keyout "${NAME}.key" -out "${NAME}.csr" \
    -subj "/C=US/ST=State/L=City/O=SignalCorp/OU=Services/CN=${CN}"

  if [ -n "$SAN" ]; then
    openssl x509 -req -days 365 -in "${NAME}.csr" \
      -CA "${CA_NAME}.crt" -CAkey "${CA_NAME}.key" -CAcreateserial \
      -out "${NAME}.crt" \
      -extfile <(printf "subjectAltName=%s" "$SAN")
  else
    openssl x509 -req -days 365 -in "${NAME}.csr" \
      -CA "${CA_NAME}.crt" -CAkey "${CA_NAME}.key" -CAcreateserial \
      -out "${NAME}.crt"
  fi

  # Convert private key to PKCS#8 format required by Netty / gRPC Java
  openssl pkcs8 -topk8 -nocrypt -in "${NAME}.key" -out "${NAME}.pem"

  rm -f "${NAME}.csr"
}

# 2. Server Certificate (signed by Trusted Root CA with SAN localhost, 127.0.0.1)
generate_cert "server" "localhost" "ca" "DNS:localhost,IP:127.0.0.1"

# 3. Trusted Client Certificates (signed by Trusted Root CA)
generate_cert "alpha-client" "alpha-client" "ca" ""
generate_cert "beta-client" "beta-client" "ca" ""

# 4. Rogue / Untrusted CA (to demonstrate mTLS rejection at TLS handshake layer)
echo "--> Generating Untrusted / Rogue Root CA..."
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout rogue-ca.key -out rogue-ca.crt \
  -subj "/C=US/ST=State/L=City/O=RogueCorp/OU=Attacker/CN=Rogue-RootCA"

# 5. Untrusted Client Certificate (signed by Rogue CA)
generate_cert "untrusted-client" "untrusted-client" "rogue-ca" ""

echo "=== mTLS Certificates generated successfully in $DIR ==="
ls -la *.crt *.pem
