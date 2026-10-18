#!/usr/bin/env bash
# Guard "the server's placeholder is never shown" (plan AN-46), CI step next to
# check-crypto-hygiene.sh. Run from the repository root: scripts/check-e2ee-entry-points.sh
#
# An encrypted message carries the server's placeholder in `content` until MessageDecryptor
# (through E2eeMessages) has handled it. Every place that
#   1. decodes messages from the server (Message / MessageFrame / MessagesInChannel serializers),
#   2. writes into StoatAPI.messageCache, or
#   3. creates a ChannelScreenItem.RegularMessage
# must take messages that went through E2eeMessages (the socket handlers and the channel routes
# call it). The reviewed places are listed in scripts/e2ee-entry-points.txt by file and line text
# (no line numbers). A new place fails this check: route it through E2eeMessages, then add it to
# the list. The render guard in Message.kt (EncryptedMessageNotice) is the second line of defence.
set -euo pipefail

cd "$(dirname "$0")/.."
list=scripts/e2ee-entry-points.txt

found=$(
    {
        grep -rnE '(Message|MessageFrame|MessagesInChannel)\.serializer\(\)' --include=*.kt \
            app/src/main core/api/src/commonMain core/api/src/androidMain | sed 's/^/decode|/'
        grep -rnE 'messageCache(\[[^]]*\] *=|\.put)' --include=*.kt app/src/main core/api/src/commonMain | sed 's/^/cache|/'
        grep -rn 'ChannelScreenItem\.RegularMessage(' --include=*.kt app/src/main | sed 's/^/item|/'
    } | grep -v '/fork/e2ee/' \
      | sed -E 's/^([a-z]+)\|([^:]+):[0-9]+:[[:space:]]*/\1|\2|/' \
      | sort
)

missing=$(comm -23 <(echo "$found") <(grep -v '^#' "$list" | grep -v '^$' | sort) || true)
if [[ -n "$missing" ]]; then
    echo "Message entry points not reviewed for E2EE (plan AN-46):"
    echo "$missing"
    echo "Route them through E2eeMessages (MessageDecryptor), then add them to $list."
    exit 1
fi

stale=$(comm -13 <(echo "$found") <(grep -v '^#' "$list" | grep -v '^$' | sort) || true)
if [[ -n "$stale" ]]; then
    echo "Note: entries in $list that no longer exist (remove them):"
    echo "$stale"
fi
echo "E2EE entry points ok."
