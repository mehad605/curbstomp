#!/usr/bin/env bash
set -euo pipefail

# Validates a SemVer tag/input (e.g. v1.2.3, 1.2.3, v1.2.3-rc.1) and emits
# normalized release metadata to stdout (or $GITHUB_OUTPUT when --github-output is passed).

usage() {
  echo "Usage: $0 <raw-version> [--github-output]" >&2
  exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage
RAW_INPUT="$1"
WRITE_GITHUB_OUTPUT=false
if [[ $# -eq 2 ]]; then
  [[ "$2" == "--github-output" ]] || usage
  WRITE_GITHUB_OUTPUT=true
fi

# Reject embedded newlines or whitespace before regex matching
if [[ "$RAW_INPUT" =~ [[:space:]] ]]; then
  echo "Error: Version '$RAW_INPUT' must not contain whitespace." >&2
  exit 1
fi

SEMVER_REGEX='^v?([0-9]+)\.([0-9]+)\.([0-9]+)(-[0-9A-Za-z]+(\.[0-9A-Za-z-]+)*)?$'
if [[ ! "$RAW_INPUT" =~ $SEMVER_REGEX ]]; then
  echo "Error: Invalid SemVer '$RAW_INPUT'. Expected format: vMAJOR.MINOR.PATCH[-prerelease] (e.g. v1.2.0 or v1.2.0-rc.1)." >&2
  exit 1
fi

# Force base-10 arithmetic so leading zeros (e.g. 08, 09) never trigger octal errors
MAJOR=$((10#${BASH_REMATCH[1]}))
MINOR=$((10#${BASH_REMATCH[2]}))
PATCH=$((10#${BASH_REMATCH[3]}))
PRERELEASE_SUFFIX="${BASH_REMATCH[4]:-}"

if (( MAJOR > 2000 || MINOR > 99 || PATCH > 99 )); then
  echo "Error: Version components out of range (major<=2000, minor<=99, patch<=99): $RAW_INPUT" >&2
  exit 1
fi

if (( MAJOR == 0 && MINOR == 0 && PATCH == 0 )); then
  echo "Error: Version 0.0.0 is not allowed." >&2
  exit 1
fi

IS_PRERELEASE=false
PRERELEASE_OFFSET=1
if [[ -n "$PRERELEASE_SUFFIX" ]]; then
  IS_PRERELEASE=true
  PRERELEASE_OFFSET=0
fi

# Monotonic Android versionCode:
# - Existing v1.0.0 used 10000.
# - Starting at v1.0.1+: (major*10000 + minor*100 + patch)*10 + (0 for prerelease, 1 for stable),
#   ensuring pre-releases always cleanly upgrade in-place to their corresponding stable release.
VERSION_CODE=$(( (MAJOR * 10000 + MINOR * 100 + PATCH) * 10 + PRERELEASE_OFFSET ))
CLEAN_VERSION="${MAJOR}.${MINOR}.${PATCH}${PRERELEASE_SUFFIX}"
VERSION="v${CLEAN_VERSION}"
APK_NAME="Curbstomp-${VERSION}.apk"

emit() {
  local key="$1"
  local value="$2"
  echo "${key}=${value}"
  if [[ "$WRITE_GITHUB_OUTPUT" == "true" ]]; then
    echo "${key}=${value}" >> "${GITHUB_OUTPUT:?GITHUB_OUTPUT must be set}"
  fi
}

emit "version" "$VERSION"
emit "clean_version" "$CLEAN_VERSION"
emit "version_code" "$VERSION_CODE"
emit "prerelease" "$IS_PRERELEASE"
emit "apk_name" "$APK_NAME"
