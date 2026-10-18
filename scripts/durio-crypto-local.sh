#!/usr/bin/env bash
# Copies a local build of the E2EE core into .durio-crypto/ (gitignored), plan AN-20:
#   .durio-crypto/maven/  maven repo with io.github.dakarxyz.durio:stoat-crypto (settings.gradle.kts
#                         prefers it over the GitHub Release)
#   .durio-crypto/host/   libstoat_crypto_ffi.so for linux-x86-64: JVM tests against the real core
# Inside the for-android checkout, so builds in Docker (only /work mounted) see it too.
#
# Usage: scripts/durio-crypto-local.sh [path to stoat-crypto]   (default: ../stoat-crypto)
# Build the core first: stoat-crypto/scripts/daar.sh (Windows) or scripts/build-aar.sh all.
# The AAR must match the SHA-256 pinned in gradle/verification-metadata.xml; a new core version
# means a new pin (and a version bump in gradle/libs.versions.toml).
set -euo pipefail

cd "$(dirname "$0")/.."
src=${1:-../stoat-crypto}
android="$src/bindings/android/build"

test -d "$android/maven" || { echo "No $android/maven: build the AAR first (stoat-crypto scripts/daar.sh)"; exit 1; }

rm -rf .durio-crypto
mkdir -p .durio-crypto/maven .durio-crypto/host
cp -R "$android/maven/." .durio-crypto/maven/
if [[ -f "$android/host/libstoat_crypto_ffi.so" ]]; then
    cp "$android/host/libstoat_crypto_ffi.so" .durio-crypto/host/
else
    echo "Note: no host library in $android/host, JVM tests against the real core will be skipped"
fi

version=$(sed -nE 's/^stoat-crypto = "([^"]+)"/\1/p' gradle/libs.versions.toml)
aar=".durio-crypto/maven/io/github/dakarxyz/durio/stoat-crypto/$version/stoat-crypto-$version.aar"
test -f "$aar" || { echo "No AAR for version $version (gradle/libs.versions.toml) in $android/maven"; exit 1; }

sha=$(sha256sum "$aar" | cut -d' ' -f1)
if grep -q "$sha" gradle/verification-metadata.xml; then
    echo "stoat-crypto $version ($sha) copied; matches the pin."
else
    echo "stoat-crypto $version copied, but its SHA-256 $sha is not pinned in gradle/verification-metadata.xml."
    echo "Gradle will refuse it: add it there (also-trust for a dev build, the release value for a release)."
    exit 1
fi
