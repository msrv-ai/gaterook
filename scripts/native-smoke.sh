#!/usr/bin/env bash
# Native smoke (ARCHITECTURE.md §2.4, report 15 §6): the core's process tests against the native executable.
#
#   NativeSmokeIT       steps 1-4, 6, 7 of [15] §6 on the fake SQL connector (start and ready line, admin API, MCP with
#                       a change approved and executed, SSE, the audit after the stop, the log), the public listener
#                       (7466: outside the allowlist 404, no token 401) and the warm start (exec -> ready line, median
#                       of 21 starts within 0.5 s; -Dgateway.smoke.warmStartRuns / -Dgateway.smoke.warmStartBudgetMs)
#   ConnectorTouchesIT  step 5: every touch of META-INF/mcp-gateway/smoke/*.json of the connector modules through the
#                       core (fixtures postgres, redis, loki from the Docker test services with -Pservices)
#   CoreProcessIT       the start protocol, OS key protection, rotation/backup/Restore, signed types and policies, MCP
#                       of both eras with audit, the admin API of the screens
#   *CoreProcessIT      each connector area against its own fakes (and the touches that need them), the tunnel, the
#                       clients, the external secret stores
#
# Usage: scripts/native-smoke.sh [binary] [extra mvn args...]   (default binary: app/target/mcp-gateway-core)
# The binary comes from: mvn -pl app -am -Pnative -DskipTests package (one native build at a time, README
# "Development"). Add -Pservices with the Docker test services up (scripts/test-services.sh up) for the fixture
# touches; then run it through scripts/test-services.sh run.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
binary="${1:-$root/app/target/mcp-gateway-core}"
shift || true
if [[ ! -x "$binary" ]]; then
  echo "native-smoke: no executable core at $binary (build it with -Pnative first)" >&2
  exit 2
fi
binary="$(cd "$(dirname "$binary")" && pwd)/$(basename "$binary")"

# A module where no IT matched -Dit.test keeps its old failsafe-summary.xml; start from clean reports.
rm -rf "$root/app/target/failsafe-reports"

its='NativeSmokeIT,ConnectorTouchesIT,CoreProcessIT,SqlCoreProcessIT,MongoCoreProcessIT,RedisCoreProcessIT,SshCoreProcessIT'
its+=',LogsCoreProcessIT,KubernetesCoreProcessIT,GcpCoreProcessIT,AwsCoreProcessIT,HttpCoreProcessIT'
its+=',McpServersCoreProcessIT,TunnelProcessIT,ClientsProcessIT,SecretsManagerTouchIT'

mvn -B -ntp -f "$root/pom.xml" -pl app -am verify \
  -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test="$its" -Dit.failIfNoSpecifiedTests=false \
  -Dgateway.core.binary="$binary" "$@"
