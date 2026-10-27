#!/usr/bin/env bash
# Publishes the signed catalog as a GitHub release (ARCHITECTURE.md §7.5 "Catalogue"; written by step K6, run by R1).
#
# The core reads https://github.com/<org>/mcp-gateway-catalog/releases/latest/download/index.json and .sig, and
# resolves the artifact URLs of the index (file names) against the same folder, so one release carries the index,
# its signature, every plugin JAR and every npm lockfile the index pins.
#
#   CATALOG_REPO=acme/mcp-gateway-catalog CATALOG_KEY_ID=catalog-2026-1 CATALOG_PUBLIC_KEY=<base64url> \
#   MCP_GATEWAY_CATALOG_KEY=<seed or PEM> catalog/publish.sh            # build, sign, verify: a dry run
#   ... catalog/publish.sh --publish                                     # and create the release with gh
#
# The private key never touches the disk here: sign-index.mjs reads it from MCP_GATEWAY_CATALOG_KEY (or pass
# --key-file <pem>). The sequence follows the release that is latest now (its index.json + 1); the first release
# passes --first.
set -euo pipefail

publish=false
first=false
key_file=""
days=30
while [ $# -gt 0 ]; do
  case "$1" in
    --publish) publish=true ;;
    --first) first=true ;;
    --key-file) key_file="$2"; shift ;;
    --days) days="$2"; shift ;;
    *) echo "Unknown option $1" >&2; exit 2 ;;
  esac
  shift
done

: "${CATALOG_REPO:?Set CATALOG_REPO=<org>/mcp-gateway-catalog}"
: "${CATALOG_KEY_ID:?Set CATALOG_KEY_ID to the key id releases trust (catalog-2026-1)}"
: "${CATALOG_PUBLIC_KEY:?Set CATALOG_PUBLIC_KEY to the base64url public key releases ship}"
if [ -z "$key_file" ] && [ -z "${MCP_GATEWAY_CATALOG_KEY:-}" ]; then
  echo "No signing key: set MCP_GATEWAY_CATALOG_KEY or pass --key-file." >&2
  exit 2
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
dist="$(mktemp -d)"
trap 'rm -rf "$dist"' EXIT
mkdir -p "$dist/assets" "$dist/out"

echo "Building the plugins of the catalog"
(cd "$root" && mvn -B -ntp -q -pl plugins/jira-service-desk,plugins/clickhouse,plugins/rabbitmq -am package -DskipTests)

# The catalog's file names of the built JARs (the index names them in artifact.url).
cp "$root"/plugins/jira-service-desk/target/plugins-jira-service-desk-*-1.3.0.jar "$dist/assets/gw-jira-service-desk-1.3.0.jar"
cp "$root/plugins/clickhouse/target/gw-clickhouse-0.6.1.jar" "$dist/assets/"
cp "$root/plugins/rabbitmq/target/gw-rabbitmq-0.3.0.jar" "$dist/assets/"
cp "$root"/catalog/mcp-servers/*.package-lock.json "$dist/assets/"

sequence=()
if [ "$first" = true ]; then
  sequence=(--sequence 1)
else
  curl -fsSL "https://github.com/$CATALOG_REPO/releases/latest/download/index.json" -o "$dist/previous.json"
  sequence=(--previous "$dist/previous.json")
fi

key=()
if [ -n "$key_file" ]; then
  key=(--key-file "$key_file")
fi

node "$root/catalog/sign-index.mjs" sign --index "$root/catalog/index.json" --artifacts "$dist/assets" \
  --out "$dist/out" --key-id "$CATALOG_KEY_ID" --days "$days" "${sequence[@]}" "${key[@]}"
node "$root/catalog/sign-index.mjs" verify --index "$dist/out/index.json" --sig "$dist/out/index.json.sig" \
  --public-key "$CATALOG_PUBLIC_KEY"

seq="$(node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")).sequence)' "$dist/out/index.json")"
tag="catalog-$seq"
if [ "$publish" != true ]; then
  echo "Dry run: would create release $tag on $CATALOG_REPO with:"
  ls -l "$dist/out" "$dist/assets"
  exit 0
fi
gh release create "$tag" --repo "$CATALOG_REPO" --title "Catalog $seq" --latest \
  --notes "Signed catalog index, sequence $seq (key $CATALOG_KEY_ID)." \
  "$dist/out/index.json" "$dist/out/index.json.sig" "$dist"/assets/*
echo "Published $tag."
