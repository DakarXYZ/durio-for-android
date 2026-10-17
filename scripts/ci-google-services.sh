#!/usr/bin/env bash
# Writes a placeholder google-services.json for CI builds (plan AN-14): the google-services
# plugin needs one, unit tests never reach Firebase. Not a working config; real files stay out
# of git (CLAUDE.md, google_services/ of the workspace).
# Usage: scripts/ci-google-services.sh app/google-services.json
set -euo pipefail

out=${1:?output path}
client() {
    cat <<JSON
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:$2",
        "android_client_info": { "package_name": "$1" }
      },
      "oauth_client": [],
      "api_key": [{ "current_key": "ci-placeholder" }],
      "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
    }
JSON
}

cat > "$out" <<JSON
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "durio-ci-placeholder",
    "storage_bucket": "durio-ci-placeholder.appspot.com"
  },
  "client": [
$(client io.github.dakarxyz.durio 0000000000000000000000),
$(client io.github.dakarxyz.durio.debug 0000000000000000000001)
  ],
  "configuration_version": "1"
}
JSON
echo "Placeholder written to $out"
