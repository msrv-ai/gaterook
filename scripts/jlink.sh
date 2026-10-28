#!/usr/bin/env bash
# Keep the existing Unix entry point; the portable implementation also runs directly on Windows.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec node "$root/scripts/jlink.mjs" "$@"
