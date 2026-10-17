#!/usr/bin/env bash
# Fails on upstream hosts hard-coded outside the allowlist (plan AN-07; CI step in AN-14).
# Run from the repository root: scripts/check-upstream-hosts.sh
set -euo pipefail

cd "$(dirname "$0")/.."

patterns=$(mktemp)
trap 'rm -f "$patterns"' EXIT
grep -vE '^[[:space:]]*(#|$)' scripts/upstream-hosts.allowlist > "$patterns"

hits=$(grep -rnE 'stoat\.chat|revolt\.chat|stoatusercontent|revoltusercontent|stt\.gg|rvlt\.gg' \
    app/src core/*/src --include=*.kt --include=*.xml | grep -vE -f "$patterns" || true)

if [[ -n "$hits" ]]; then
    echo "Upstream hosts outside scripts/upstream-hosts.allowlist:"
    echo "$hits"
    exit 1
fi
echo "No hard-coded upstream hosts."
