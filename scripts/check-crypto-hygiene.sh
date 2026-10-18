#!/usr/bin/env bash
# E2EE hygiene (plan AN-20, CLAUDE.md "never log plaintext, keys, sessions"); CI step.
# Run from the repository root: scripts/check-crypto-hygiene.sh
#
# 1. UniFFI types stay inside :core:crypto. Their data classes print every field in toString()
#    (DecryptedMessage.payloadJson, DecryptResult.Decrypted, StoreConfig.key,
#    RecoverySetup.recoveryKey, EncryptedAttachment.fileJson, KeyExport.data), so they must not
#    travel to code that logs.
# 2. :core:crypto does not log and does not build strings from values at all — so none of those
#    types can reach a string there either (review O-5).
# 3. E2EE code elsewhere never puts bodies, keys, payloads, plaintext, key files, passphrases or
#    decrypt results into a log line.
# 4. Review O-3: the app never sets "verified" by hand; Trust.VERIFIED in :core:crypto only in
#    comparisons (a device is verified by SAS Done).
# 5. Facade types that carry secrets (SECRET_TYPES below) are plain classes with their own
#    toString(), never data classes, so a value of them in a string or log line prints no
#    secret (review O-5); SecretToStringTest in :core:api checks what their toString() prints.
# 6. No bridge of the core's `tracing` to Logcat (review O-6): the core and its FFI install no
#    subscriber and log nothing; should one ever be added it must be capped at WARN.
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

hits=$(grep -rniE '(Logger\.[a-z]+|logcat|Log\.[a-z]+)\b.*\$\{?[a-z.]*(body|key|payload|plaintext|secret|envelope|content|decrypted|result|setup|export|import|passphrase|recovery|report|attachment|file|blob)' \
    app/src/main/java/chat/stoat/fork/e2ee app/src/main/java/chat/stoat/fork/push \
    core/api/src/commonMain/kotlin/chat/stoat/api/fork/e2ee \
    core/api/src/commonMain/kotlin/chat/stoat/api/fork/routes/e2ee --include=*.kt 2>/dev/null || true)
if [[ -n "$hits" ]]; then
    echo "E2EE data in log lines:"
    echo "$hits"
    fail=1
fi

# 5. Secret-carrying facade types are no data classes (E2eeDecryptResult.Decrypted included)
SECRET_TYPES='E2eeDecryptResult|E2eeRecoverySetup|E2eeRecoverySecrets|E2eeKeyExport|E2eeKeyImport|E2eeEncryptedBlob|E2eeFileKey|E2eePayload|E2eePayloadAttachment|E2eePayloadThumbnail|E2eeAttachmentKeys|E2eeIncomingMessage|E2eeRequest'
hits=$(grep -rnE "data class ($SECRET_TYPES)\(" core/api/src app/src --include=*.kt || true)
more=$(grep -nE 'data class Decrypted\(' core/api/src/commonMain/kotlin/chat/stoat/api/fork/e2ee/E2eeDecryption.kt || true)
if [[ -n "$more" ]]; then hits=$(printf '%s\n%s' "$hits" "$more"); fi
if [[ -n "$hits" ]]; then
    echo "Secret-carrying types as data classes (toString prints every field):"
    echo "$hits"
    fail=1
fi

hits=$(grep -rnE 'Trust\.VERIFIED' core/crypto/src/main --include=*.kt \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)' | grep -vE '[=!]= *Trust\.VERIFIED' || true)
if [[ -n "$hits" ]]; then
    echo "Trust.VERIFIED set by the app (only SAS Done verifies, review O-3):"
    echo "$hits"
    fail=1
fi

# 6. A tracing → Logcat bridge would show up as one of these calls (none today)
hits=$(grep -rnE 'tracing|setLogger|initLogging|init_logging|RustLog|logLevel' core/crypto/src/main --include=*.kt \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)' || true)
if [[ -n "$hits" ]]; then
    echo "A log bridge of the core (review O-6: cap it at WARN, then allow it here):"
    echo "$hits"
    fail=1
fi

if [[ $fail -ne 0 ]]; then exit 1; fi
echo "E2EE hygiene ok."
