#!/usr/bin/env bash
# E2EE hygiene (plan AN-20, CLAUDE.md "never log plaintext, keys, sessions"); CI step.
# Run from the repository root: scripts/check-crypto-hygiene.sh
#
# 1. UniFFI types stay inside :core:crypto. Their data classes print every field in toString()
#    (DecryptedMessage.payloadJson, StoreConfig.key, RecoverySetup.recoveryKey), so they must not
#    travel to code that logs.
# 2. :core:crypto does not log and does not build strings from values at all.
# 3. E2EE code elsewhere never puts bodies, keys, payloads or plaintext into a log line.
set -euo pipefail

cd "$(dirname "$0")/.."
fail=0

hits=$(grep -rnE '^import uniffi\.|uniffi\.stoat_crypto\.' app/src core/api/src core/model/src --include=*.kt || true)
if [[ -n "$hits" ]]; then
    echo "UniFFI types outside :core:crypto:"
    echo "$hits"
    fail=1
fi

hits=$(grep -rnE 'Logger\.|logcat|android\.util\.Log|println|printStackTrace|toString\(\)|"[^"]*\$' core/crypto/src/main --include=*.kt \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)' || true)
if [[ -n "$hits" ]]; then
    echo "Logging or string building in :core:crypto:"
    echo "$hits"
    fail=1
fi

hits=$(grep -rniE '(Logger\.[a-z]+|logcat|Log\.[a-z]+)\b.*\$\{?[a-z.]*(body|key|payload|plaintext|secret|envelope|content)' \
    app/src/main/java/chat/stoat/fork/e2ee core/api/src/commonMain/kotlin/chat/stoat/api/fork/e2ee \
    core/api/src/commonMain/kotlin/chat/stoat/api/fork/routes/e2ee --include=*.kt 2>/dev/null || true)
if [[ -n "$hits" ]]; then
    echo "E2EE data in log lines:"
    echo "$hits"
    fail=1
fi

if [[ $fail -ne 0 ]]; then exit 1; fi
echo "E2EE hygiene ok."
