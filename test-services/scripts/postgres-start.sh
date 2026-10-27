#!/bin/sh
# PostgreSQL of the test services with TLS: the server refuses a key other users can read, and a bind mount from the
# host can't promise that, so the certificate and key are copied with the owner and mode it wants first.
set -eu
install -o postgres -g postgres -m 600 /certs/postgres.key /var/lib/postgresql/server.key
install -o postgres -g postgres -m 644 /certs/postgres.crt /var/lib/postgresql/server.crt
exec docker-entrypoint.sh postgres \
  -c ssl=on -c ssl_cert_file=/var/lib/postgresql/server.crt -c ssl_key_file=/var/lib/postgresql/server.key \
  -c fsync=off -c full_page_writes=off -c synchronous_commit=off -c max_connections=200
