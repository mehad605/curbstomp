#!/usr/bin/env bash
set -euo pipefail

# Verifies a built release APK before publishing:
#   1. File exists and is non-empty
#   2. APK signature is valid via apksigner (and NOT signed with Android Debug cert unless --allow-debug-cert)
#   3. Embedded applicationId, versionCode, and versionName match expected values via aapt2
#   4. Contains zero native .so libraries (regression guard)

usage() {
  echo "Usage: $0 <apk-path> <expected-version-name> <expected-version-code> [--allow-debug-cert]" >&2
  exit 2
}

[[ $# -ge 3 && $# -le 4 ]] || usage
APK_PATH="$1"
EXPECTED_VERSION_NAME="$2"
EXPECTED_VERSION_CODE="$3"
ALLOW_DEBUG_CERT=false
if [[ $# -eq 4 ]]; then
  [[ "$4" == "--allow-debug-cert" ]] || usage
  ALLOW_DEBUG_CERT=true
fi

if [[ ! -f "$APK_PATH" || ! -s "$APK_PATH" ]]; then
  echo "Error: APK not found or empty: $APK_PATH" >&2
  exit 1
fi

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_ROOT" || ! -d "$SDK_ROOT/build-tools" ]]; then
  echo "Error: ANDROID_HOME/ANDROID_SDK_ROOT with build-tools is required." >&2
  exit 1
fi

BUILD_TOOLS_DIR="$(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
APKSIGNER="${BUILD_TOOLS_DIR}/apksigner"
AAPT2="${BUILD_TOOLS_DIR}/aapt2"

if [[ ! -x "$APKSIGNER" || ! -x "$AAPT2" ]]; then
  echo "Error: apksigner or aapt2 not found in $BUILD_TOOLS_DIR" >&2
  exit 1
fi

echo "==> Verifying APK signature with $APKSIGNER"
CERT_INFO="$("$APKSIGNER" verify --verbose --print-certs "$APK_PATH")"
echo "$CERT_INFO"

if [[ "$ALLOW_DEBUG_CERT" != "true" ]]; then
  if echo "$CERT_INFO" | grep -qi "CN=Android Debug"; then
    echo "Error: Refusing to publish a debug-signed APK!" >&2
    exit 1
  fi
fi

echo "==> Verifying manifest metadata with $AAPT2"
FULL_BADGING="$("$AAPT2" dump badging "$APK_PATH")"
PACKAGE_LINE="${FULL_BADGING%%$'\n'*}"
echo "$PACKAGE_LINE"

EXPECTED_PACKAGE_LINE="package: name='com.mhm.curbstomp' versionCode='${EXPECTED_VERSION_CODE}' versionName='${EXPECTED_VERSION_NAME}'"
if [[ "$PACKAGE_LINE" != *"$EXPECTED_PACKAGE_LINE"* ]]; then
  echo "Error: APK metadata mismatch." >&2
  echo "  Expected: $EXPECTED_PACKAGE_LINE" >&2
  echo "  Actual:   $PACKAGE_LINE" >&2
  exit 1
fi

echo "==> Verifying zero native .so libraries"
SO_COUNT="$(unzip -Z1 "$APK_PATH" | grep -c '\.so$' || true)"
if (( SO_COUNT != 0 )); then
  echo "Error: Unexpected native .so libraries found in APK ($SO_COUNT)." >&2
  exit 1
fi

APK_BYTES="$(wc -c < "$APK_PATH" | tr -d ' ')"
echo "==> Verified OK: $APK_PATH (${APK_BYTES} bytes, versionName=${EXPECTED_VERSION_NAME}, versionCode=${EXPECTED_VERSION_CODE})"
