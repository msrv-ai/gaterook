#!/usr/bin/env bash
# The quick AOT detector (ARCHITECTURE.md §2.4): what breaks the native image at run time, found without building it.
#
#   1. process-aot of `-Pnative` without the image build (-DskipNativeBuild=true); a repository method Spring Data
#      could not compile ahead of time ("Failed to contribute Repository method") falls back to reflection that the
#      image doesn't have, so its line in the log fails the check;
#   2. the AOT-processed context on the JVM (-Dspring.aot.enabled=true): NativeSmokeIT starts app.jar that way
#      (CoreProcess, -Dgateway.core.aot=true), so bean definitions AOT got wrong fail here, not in the binary.
#
# Usage: scripts/aot-check.sh [extra mvn args...]; the log goes to app/target/aot-check.log.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
log="$root/app/target/aot-check.log"
mkdir -p "$root/app/target"
rm -rf "$root/app/target/failsafe-reports"

set +e
mvn -B -ntp -f "$root/pom.xml" -pl app -am -Pnative -DskipNativeBuild=true verify \
  -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=NativeSmokeIT -Dit.failIfNoSpecifiedTests=false -Dgateway.core.aot=true "$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
set -e

if grep -q 'Failed to contribute Repository method' "$log"; then
  echo "aot-check: process-aot could not compile repository methods ahead of time:" >&2
  grep 'Failed to contribute Repository method' "$log" >&2
  exit 1
fi
if [[ ! -d "$root/app/target/spring-aot" ]]; then
  echo "aot-check: process-aot did not run (no app/target/spring-aot)" >&2
  exit 1
fi
# process-aot belongs to app only (ARCHITECTURE.md §2.4): another module with spring-aot output would ship it twice.
others="$(find "$root" -path "$root/app" -prune -o -path '*/node_modules' -prune -o -type d -name spring-aot \
  -path '*/target/*' -print)"
if [[ -n "$others" ]]; then
  echo "aot-check: spring-aot output outside app/target:" >&2
  echo "$others" >&2
  exit 1
fi
exit "$status"
