#!/bin/sh
# Apache Cassandra of the test services (profile cassandra). The image has no variable for its authenticator and
# authorizer, so this edits cassandra.yaml before the image's own entrypoint starts the node:
#   - password sign-in with roles and permissions (PasswordAuthenticator, CassandraAuthorizer), not AllowAll;
#   - TLS on the native port next to plain CQL (optional: true), so the health check and the IT's setup need no TLS.
# The node's certificate is made once into /certs (run/cassandra-certs): node.crt is self-signed for localhost and
# 127.0.0.1 and is the CA the IT trusts; other.crt signed nothing, for "wrong CA" checks. Test-only keys.
set -eu
conf=/etc/cassandra/cassandra.yaml
pass="$GW_CASSANDRA_KEYSTORE_PASSWORD"

if [ ! -s /certs/node.p12 ] || [ ! -s /certs/node.crt ] || [ ! -s /certs/other.crt ]; then
  rm -f /certs/node.p12 /certs/node.crt /certs/other.crt
  keytool -genkeypair -alias node -keyalg RSA -keysize 2048 -validity 3650 -dname CN=localhost \
    -ext san=dns:localhost,ip:127.0.0.1 -keystore /certs/node.p12 -storetype PKCS12 \
    -storepass "$pass" -keypass "$pass"
  keytool -exportcert -rfc -alias node -keystore /certs/node.p12 -storetype PKCS12 -storepass "$pass" \
    -file /certs/node.crt
  keytool -genkeypair -alias other -keyalg RSA -keysize 2048 -validity 3650 -dname CN=gw-test-other \
    -keystore /tmp/other.p12 -storetype PKCS12 -storepass "$pass" -keypass "$pass"
  keytool -exportcert -rfc -alias other -keystore /tmp/other.p12 -storetype PKCS12 -storepass "$pass" \
    -file /certs/other.crt
  rm -f /tmp/other.p12
  # Cassandra runs as its own user: the test keystore stays readable to it.
  chmod 644 /certs/node.p12 /certs/node.crt /certs/other.crt
fi

sed -i \
  -e 's/^authenticator: AllowAllAuthenticator$/authenticator: PasswordAuthenticator/' \
  -e 's/^authorizer: AllowAllAuthorizer$/authorizer: CassandraAuthorizer/' \
  -e '/^client_encryption_options:/,/^$/{
        s/^  enabled: false$/  enabled: true/
        s/^  # optional: true$/  optional: true/
        s#^  keystore: conf/.keystore$#  keystore: /certs/node.p12#
        s/^  #keystore_password: cassandra$/  keystore_password: '"$pass"'/
        s/^  # store_type: JKS$/  store_type: PKCS12/
      }' "$conf"
for line in '^authenticator: PasswordAuthenticator$' '^authorizer: CassandraAuthorizer$' \
    '^  keystore: /certs/node.p12$' '^  optional: true$' '^  store_type: PKCS12$'; do
  grep -q "$line" "$conf" || { echo "cassandra.yaml doesn't have $line: the image changed" >&2; exit 1; }
done

exec docker-entrypoint.sh cassandra -f
