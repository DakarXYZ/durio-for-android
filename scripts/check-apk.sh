#!/usr/bin/env bash
# APK checks of plan AN-41:
#   - native libraries 16 KB page aligned (`zipalign -c -P 16`), required by Android 15+ devices
#     with 16 KB pages; .so files stay uncompressed (useLegacyPackaging = false);
#   - no lib folder of an ABI we do not ship (x86, armeabi, mips...: no E2EE core there);
#   - with --release: the arm64 APK within the size budget (stage 1 + 15 MB, C23).
#
# Usage: scripts/check-apk.sh [--release] <apk>...
# zipalign comes from $BUILD_TOOLS, else the newest build-tools in $ANDROID_HOME.
set -euo pipefail

# arm64-v8a release APK of durio/stage-1 (no E2EE core) built with the same splits.
# TODO(AN-41): not measured yet; 0 skips the size check. Budget: + 15 MiB.
ARM64_STAGE1_BYTES=0
ARM64_RELEASE_BUDGET=$((ARM64_STAGE1_BYTES + 15 * 1024 * 1024))
SHIPPED_ABIS="arm64-v8a armeabi-v7a x86_64"

release=false
if [[ ${1:-} == "--release" ]]; then
    release=true
    shift
fi
[[ $# -gt 0 ]] || { echo "Usage: $0 [--release] <apk>..."; exit 2; }

if [[ -z ${BUILD_TOOLS:-} ]]; then
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    [[ -n $sdk ]] || { echo "Set ANDROID_HOME or BUILD_TOOLS"; exit 2; }
    BUILD_TOOLS=$(ls -d "$sdk"/build-tools/* | sort -V | tail -n 1)
fi
zipalign="$BUILD_TOOLS/zipalign"

failed=0
for apk in "$@"; do
    name=$(basename "$apk")
    size=$(stat -c %s "$apk")
    if ! "$zipalign" -c -P 16 -v 4 "$apk" > /dev/null; then
        echo "FAIL $name: not 16 KB aligned (zipalign -c -P 16 -v 4 $apk)"
        failed=1
    fi
    abis=$(unzip -Z1 "$apk" 'lib/*' 2> /dev/null | cut -d/ -f2 | sort -u | tr '\n' ' ' || true)
    for abi in $abis; do
        if [[ " $SHIPPED_ABIS " != *" $abi "* ]]; then
            echo "FAIL $name: lib/$abi/ is packaged (excluded in app/build.gradle.kts packaging.jniLibs)"
            failed=1
        fi
    done
    if $release && ((ARM64_STAGE1_BYTES > 0)) && [[ $name == *arm64-v8a* && $name != *debug* ]] && ((size > ARM64_RELEASE_BUDGET)); then
        echo "FAIL $name: $size bytes, budget $ARM64_RELEASE_BUDGET (stage 1 + 15 MiB, AN-41)"
        failed=1
    fi
    echo "$name: $size bytes, ABIs: ${abis:-none}"
done
exit $failed
