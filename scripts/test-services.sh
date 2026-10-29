#!/usr/bin/env bash
# The real servers the connectors' integration tests can use instead of starting their own (test-services/README.md).
#
#   scripts/test-services.sh up [profile…]   start and wait until healthy; default: everything but k8s and sqlserver
#   scripts/test-services.sh down            stop and remove the containers (the data is in memory, nothing stays)
#   scripts/test-services.sh run <command…>  run a command (mvn … -Pservices) as the only user of the servers: the ITs
#                                            reset them, so two runs at once (two worktrees) would spoil each other
#   scripts/test-services.sh ps | logs [service…] | env
#
# Profiles: sql sqlserver mongo redis logs aws vault ssh k8s all. Then: mvn -B -ntp verify -Pservices
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
dir="$root/test-services"
compose=(docker compose -f "$dir/docker-compose.yml")
cmd=${1:-up}
[ $# -gt 0 ] && shift
case "$cmd" in
  up)
    profiles=("$@")
    [ ${#profiles[@]} -gt 0 ] || profiles=(sql mongo redis logs aws vault ssh)
    args=()
    for p in "${profiles[@]}"; do args+=(--profile "$p"); done
    mkdir -p "$dir/run/certs" "$dir/run/openssh" "$dir/run/k3s"
    "${compose[@]}" "${args[@]}" up -d --build --wait
    echo
    echo "Ready. Run the ITs against them: mvn -B -ntp verify -Pservices   (or export GW_TEST_SERVICES=$dir)"
    ;;
  down)
    "${compose[@]}" --profile all down --remove-orphans
    ;;
  ps)
    "${compose[@]}" --profile all ps
    ;;
  logs)
    "${compose[@]}" --profile all logs "$@"
    ;;
  env)
    echo "export GW_TEST_SERVICES=$dir"
    ;;
  run)
    lock="${TMPDIR:-/tmp}/gw-test-services.lock"
    until mkdir "$lock" 2>/dev/null; do
      echo "waiting: another run uses the test services ($lock; remove it if no run is left)" >&2
      sleep 5
    done
    trap 'rmdir "$lock"' EXIT
    "$@"
    ;;
  *)
    echo "usage: $0 up [profile…] | down | run <command…> | ps | logs [service…] | env" >&2
    exit 2
    ;;
esac
