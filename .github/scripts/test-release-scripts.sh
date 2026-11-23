#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOLVER="${SCRIPT_DIR}/resolve-release-version.sh"

assert_ok() {
  local input="$1"
  local exp_ver="$2"
  local exp_clean="$3"
  local exp_code="$4"
  local exp_pre="$5"

  local out
  out="$("$RESOLVER" "$input")"
  echo "$out" | grep -Fxq "version=${exp_ver}" || { echo "FAIL($input): expected version=${exp_ver}, got: $out" >&2; exit 1; }
  echo "$out" | grep -Fxq "clean_version=${exp_clean}" || { echo "FAIL($input): expected clean_version=${exp_clean}, got: $out" >&2; exit 1; }
  echo "$out" | grep -Fxq "version_code=${exp_code}" || { echo "FAIL($input): expected version_code=${exp_code}, got: $out" >&2; exit 1; }
  echo "$out" | grep -Fxq "prerelease=${exp_pre}" || { echo "FAIL($input): expected prerelease=${exp_pre}, got: $out" >&2; exit 1; }
}

assert_fail() {
  local input="$1"
  if "$RESOLVER" "$input" >/dev/null 2>&1; then
    echo "FAIL($input): expected rejection, but succeeded" >&2
    exit 1
  fi
}

# Valid inputs
assert_ok "v1.1.0" "v1.1.0" "1.1.0" "101001" "false"
assert_ok "1.1.0" "v1.1.0" "1.1.0" "101001" "false"
assert_ok "v1.08.09" "v1.8.9" "1.8.9" "108091" "false"
assert_ok "v1.2.0-rc.1" "v1.2.0-rc.1" "1.2.0-rc.1" "102000" "true"
assert_ok "v1.2.0" "v1.2.0" "1.2.0" "102001" "false"
assert_ok "v2.0.0-beta.2" "v2.0.0-beta.2" "2.0.0-beta.2" "200000" "true"
assert_ok "v0.1.0" "v0.1.0" "0.1.0" "1001" "false"

# Invalid / malicious inputs
assert_fail ""
assert_fail "v1.2"
assert_fail "v1.2.3.4"
assert_fail "v0.0.0"
assert_fail "v1.100.0"
assert_fail "v1.0.100"
assert_fail "v1.2.3 extra"
assert_fail $'v1.2.3\nFOO=bar'
assert_fail "../v1.2.3"
assert_fail "v1.2.3-"
assert_fail "abc"

echo "All release version script tests passed!"
