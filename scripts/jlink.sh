#!/usr/bin/env bash
# The Java runtime of the plugin host (ARCHITECTURE.md A12, report 16 §3.2): a jlink image of the JDK that runs this
# script. Plugins are other people's code with unknown jdeps, so the runtime is java.se plus the jdk modules they
# commonly need; a manifest's requires.javaModules is checked against the MODULES= line of its release file. The
# release file also gets JAVA_RUNTIME_VERSION (jlink writes only JAVA_VERSION): with the version of plugin-host.jar
# and its SHA-256 it is the key of the host's AOT cache (AotCacheManager).
#
#   scripts/jlink.sh [output folder]     default: target/plugin-host-jre, which scripts/stage.mjs copies into the stage
#
# JAVA_HOME must be the JDK 25 the runtime is made of (the same major as the core's build).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="${1:-$root/target/plugin-host-jre}"
java_home="${JAVA_HOME:?set JAVA_HOME to a JDK 25}"

modules=java.se,jdk.unsupported,jdk.crypto.cryptoki,jdk.zipfs,jdk.net,jdk.charsets,jdk.localedata,jdk.security.auth
modules+=,jdk.security.jgss,jdk.naming.dns,jdk.management,jdk.httpserver,jdk.jfr,jdk.management.jfr

version="$("$java_home/bin/java" -XshowSettings:properties -version 2>&1 \
  | awk -F' = ' '$1 ~ /^ *java\.runtime\.version$/ {print $2}')"
if [[ -z "$version" ]]; then
  echo "jlink.sh: can't read java.runtime.version of $java_home" >&2
  exit 2
fi
major="${version%%[.+]*}"
if [[ "$major" != "25" ]]; then
  echo "jlink.sh: $java_home is Java $version; the plugin host runs on Java 25" >&2
  exit 2
fi

rm -rf "$out"
mkdir -p "$(dirname "$out")"
"$java_home/bin/jlink" --add-modules "$modules" --include-locales=en,ru --strip-debug --no-man-pages \
  --no-header-files --compress=zip-6 --release-info="add:JAVA_RUNTIME_VERSION=$version" --output "$out"

echo "jlink.sh: $out ($(du -sh "$out" | cut -f1)), JAVA_RUNTIME_VERSION=$version"
