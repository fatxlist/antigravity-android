#!/usr/bin/env bash
# 06_package.sh - Packages Android resources, assets, and classes.dex into an aligned APK in one pass.
set -euo pipefail

export ANDROID_DATA="${ANDROID_DATA:-/data}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_APK="${1:-$BUILD_DIR/aligned.apk}"
MANIFEST="${2:-$APP_DIR/src/main/AndroidManifest.xml}"
RES_DIR="${3:-$APP_DIR/src/main/res}"
ANDROID_JAR="${4:-$TOOLS_DIR/android.jar}"
STAGE_ASSETS="${5:-$BUILD_DIR/staging/assets}"
DEX_FILE="${6:-$BUILD_DIR/dex/classes.dex}"
BUILD_RES_DIR="${7:-${BUILD_DIR:-}/res}"

BASE_APK="${OUT_APK}.base.tmp"
trap 'rm -f "$BASE_APK"' EXIT

RES_ARGS=()
if [ -n "${BUILD_RES_DIR:-}" ] && [ -d "$BUILD_RES_DIR" ]; then
    RES_ARGS+=(-S "$BUILD_RES_DIR")
fi
RES_ARGS+=(-S "$RES_DIR" --auto-add-overlay)

echo "[*] [06_package] Packaging base resources into temporary APK..."
aapt package -f \
    -M "$MANIFEST" \
    "${RES_ARGS[@]}" \
    -I "$ANDROID_JAR" \
    -F "$BASE_APK"

PACKAGE_FLAGS=("--alignment" "4")
if [ "${FAST_BUILD:-0}" = "1" ]; then
    PACKAGE_FLAGS+=("--fast-store")
fi

echo "[*] [06_package] Streaming classes.dex and assets into 4-byte/4KB aligned APK..."
python3 -B "$SCRIPT_DIR/align.py" --package "$BASE_APK" "$OUT_APK" "$DEX_FILE" "$STAGE_ASSETS" "${PACKAGE_FLAGS[@]}"

echo "[OK] [06_package] Created $(stat -c%s "$OUT_APK") bytes aligned APK"
