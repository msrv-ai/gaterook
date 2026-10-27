#!/bin/sh
# Certificates of the test services, made once into $1 (test-services/run/certs) by the "certs" service of
# docker-compose.yml; the tests read the same files. Short-lived and test-only: delete the folder to make new ones.
#
#   ca.crt/.key            "gw-test-ca": signs the Redis and MongoDB server certificates
#   other-ca.crt/.key      "gw-test-other-ca": a CA that signed nothing, for "wrong CA" checks
#   localhost.crt/.key     server certificate for DNS:localhost only (Redis): 127.0.0.1 must fail full verification
#   localhost-ip.pem       server certificate + key for DNS:localhost and IP:127.0.0.1 (MongoDB --tlsCertificateKeyFile)
#   postgres.crt/.key      self-signed for DNS:localhost only (PostgreSQL), as the ITs' own initdb fixture makes it
set -eu
out="$1"
mkdir -p "$out"
if [ -s "$out/postgres.key" ] && [ -s "$out/localhost-ip.pem" ]; then
  echo "certificates exist in $out"
  exit 0
fi
command -v openssl >/dev/null 2>&1 || apk add --no-cache openssl >/dev/null
cd "$out"
days=3650

openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt -days "$days" -subj /CN=gw-test-ca
openssl req -x509 -newkey rsa:2048 -nodes -keyout other-ca.key -out other-ca.crt -days "$days" \
  -subj /CN=gw-test-other-ca

printf 'subjectAltName=DNS:localhost\n' > localhost.ext
openssl req -newkey rsa:2048 -nodes -keyout localhost.key -out localhost.csr -subj /CN=localhost
openssl x509 -req -in localhost.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out localhost.crt -days "$days" \
  -extfile localhost.ext

printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\n' > localhost-ip.ext
openssl req -newkey rsa:2048 -nodes -keyout localhost-ip.key -out localhost-ip.csr -subj /CN=localhost
openssl x509 -req -in localhost-ip.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out localhost-ip.crt \
  -days "$days" -extfile localhost-ip.ext
cat localhost-ip.crt localhost-ip.key > localhost-ip.pem

printf '[req]\ndistinguished_name=dn\n[dn]\n[ext]\nsubjectAltName=DNS:localhost\n' > postgres.cnf
openssl req -x509 -newkey rsa:2048 -nodes -keyout postgres.key -out postgres.crt -days "$days" -subj /CN=localhost \
  -config postgres.cnf -extensions ext

rm -f ./*.csr ./*.ext ./*.srl postgres.cnf
# The servers run as their own users (redis, mongodb): test keys stay readable to them.
chmod 644 ./*.key ./*.crt ./*.pem
echo "certificates made in $out"
