#!/usr/bin/env bash
# Run only inside the disposable Linux amd64 Wine container launched by windows-docker.mjs.
set -euo pipefail
cd /project

# Fail before installing dependencies if Wine cannot execute Windows code (including under emulation).
if ! timeout --kill-after=5s 30s wine cmd /c exit 0; then
  echo 'windows-container: Wine cannot run Windows executables. On Apple Silicon, use an x86-64 Linux Docker host if emulation fails.' >&2
  exit 1
fi

node_version="$(tr -d '[:space:]' < .nvmrc)"
if [[ ! "$node_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo 'windows-container: .nvmrc must pin a complete Node.js version' >&2
  exit 1
fi

# The mutable Wine tag may ship an older Node; install the application's pinned Linux toolchain.
if [[ "$(node --version)" != "v$node_version" ]]; then
  archive="node-v${node_version}-linux-x64.tar.xz"
  mkdir -p /tmp/gaterook-node /opt/gaterook-node
  cd /tmp/gaterook-node
  curl --fail --location --retry 3 --output "$archive" "https://nodejs.org/dist/v${node_version}/${archive}"
  curl --fail --location --retry 3 --output SHASUMS256.txt "https://nodejs.org/dist/v${node_version}/SHASUMS256.txt"
  awk -v archive="$archive" '$2 == archive { print; found = 1 } END { if (!found) exit 1 }' SHASUMS256.txt | sha256sum --check -
  tar -xJf "$archive" --strip-components=1 -C /opt/gaterook-node
  export PATH="/opt/gaterook-node/bin:$PATH"
  cd /project
fi
npm_version="$(node -p 'JSON.parse(require("node:fs").readFileSync("package.json", "utf8")).packageManager')"
if [[ ! "$npm_version" =~ ^npm@[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo 'windows-container: packageManager must pin npm' >&2
  exit 1
fi
if [[ "$(npm --version)" != "${npm_version#npm@}" ]]; then
  npm install --global "$npm_version" --no-audit --no-fund
fi

node scripts/windows-package.mjs
