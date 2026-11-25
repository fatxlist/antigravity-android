#!/usr/bin/env bash
# 08_sign.sh - Signs the aligned APK using apksigner with release or explicit dev/debug credentials.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${PROJECT_ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
BUILD_DIR="${BUILD_DIR:-$PROJECT_ROOT/build}"
DIST_DIR="${DIST_DIR:-$PROJECT_ROOT/dist}"
TOOLS_DIR="${TOOLS_DIR:-$PROJECT_ROOT/tools}"

if [ -f "$PROJECT_ROOT/config/tools.env" ]; then
    # shellcheck disable=SC1091
    source "$PROJECT_ROOT/config/tools.env"
fi

ALIGNED_APK="${1:-$BUILD_DIR/aligned.apk}"
OUT_APK="${2:-$DIST_DIR/antigravity.apk}"

if [ "${DEV_MODE:-0}" = "1" ] || [ "${ALLOW_DEBUG_SIGN:-0}" = "1" ]; then
    DEFAULT_KS="${DEV_KEYSTORE:-$TOOLS_DIR/debug.keystore}"
    DEFAULT_ALIAS="${DEV_KEY_ALIAS:-androiddebugkey}"
    DEFAULT_KEY_PASS="${DEV_KEY_PASS:-android}"
    DEFAULT_STORE_PASS="${DEV_STORE_PASS:-android}"
else
    DEFAULT_KS="${KEYSTORE:-}"
    DEFAULT_ALIAS="${KEY_ALIAS:-}"
    DEFAULT_KEY_PASS="${KEY_PASS:-}"
    DEFAULT_STORE_PASS="${STORE_PASS:-}"
fi

KEYSTORE_PATH="${3:-$DEFAULT_KS}"
KEY_ALIAS_VAL="${4:-$DEFAULT_ALIAS}"
export AGY_SIGN_KEY_PASS="${5:-${AGY_SIGN_KEY_PASS:-$DEFAULT_KEY_PASS}}"
export AGY_SIGN_STORE_PASS="${6:-${AGY_SIGN_STORE_PASS:-$DEFAULT_STORE_PASS}}"

if [ -z "$KEYSTORE_PATH" ] || [ -z "$KEY_ALIAS_VAL" ] || [ -z "$AGY_SIGN_KEY_PASS" ] || [ -z "$AGY_SIGN_STORE_PASS" ]; then
    echo "[ERROR] [08_sign] Refusing to sign release APK without signing credentials." >&2
    echo "  Provide AGY_RELEASE_KEYSTORE, AGY_RELEASE_KEY_ALIAS, AGY_RELEASE_KEY_PASS, and AGY_RELEASE_STORE_PASS," >&2
    echo "  or set DEV_MODE=1 / ALLOW_DEBUG_SIGN=1 for debug signing." >&2
    exit 1
fi

if [ ! -f "$KEYSTORE_PATH" ]; then
    echo "[ERROR] [08_sign] Signing keystore not found: $KEYSTORE_PATH" >&2
    exit 1
fi

echo "[*] [08_sign] Signing APK using alias '$KEY_ALIAS_VAL'..."
mkdir -p "$(dirname "$OUT_APK")"

apksigner sign \
    --ks "$KEYSTORE_PATH" \
    --ks-key-alias "$KEY_ALIAS_VAL" \
    --ks-pass "env:AGY_SIGN_STORE_PASS" \
    --key-pass "env:AGY_SIGN_KEY_PASS" \
    --out "$OUT_APK" \
    "$ALIGNED_APK"

echo "[OK] [08_sign] Signed APK created: $OUT_APK ($(stat -c%s "$OUT_APK") bytes)"
