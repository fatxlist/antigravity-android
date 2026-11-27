#!/usr/bin/env bash
# build_apk.sh - Master orchestrator for building, dexing, packaging, and signing Antigravity APK.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
export BUILD_DIR="$PROJECT_ROOT/build"
export DIST_DIR="$PROJECT_ROOT/dist"
export APP_DIR="$PROJECT_ROOT/app"
export TOOLS_DIR="$PROJECT_ROOT/tools"

if [ -d "/data/user/0/com.agy.dev/files" ] && [ -w "/data/user/0/com.agy.dev/files" ]; then
    export HOME="/data/user/0/com.agy.dev/files"
    export PATH="/data/user/0/com.agy.dev/files/usr/bin:$PATH"
elif [ -d "/data/user/0/com.agy/files" ] && [ -w "/data/user/0/com.agy/files" ]; then
    export HOME="/data/user/0/com.agy/files"
    export PATH="/data/user/0/com.agy/files/usr/bin:$PATH"
fi
export LANG=C.UTF-8
export LC_ALL=C.UTF-8

export FAST_BUILD=0
export DEV_MODE=0
export ALLOW_DEBUG_SIGN="${ALLOW_DEBUG_SIGN:-0}"
export REQUIRE_NATIVE="${REQUIRE_NATIVE:-0}"
export CUSTOM_PREFIX=""
export CUSTOM_OUT=""

while [ $# -gt 0 ]; do
    case "$1" in
        --fast)
            export FAST_BUILD=1
            shift
            ;;
        --dev|-d)
            export DEV_MODE=1
            shift
            ;;
        --allow-debug-sign)
            export ALLOW_DEBUG_SIGN=1
            shift
            ;;
        --require-native)
            export REQUIRE_NATIVE=1
            shift
            ;;
        --prefix)
            export CUSTOM_PREFIX="$2"
            shift 2
            ;;
        --out|-o)
            export CUSTOM_OUT="$2"
            shift 2
            ;;
        *)
            echo "[WARNING] Unknown argument: $1" >&2
            shift
            ;;
    esac
done

BASE_PREFIX="${CUSTOM_PREFIX:-antigravity}"

if [ -n "$CUSTOM_OUT" ]; then
    OUT_APK="$CUSTOM_OUT"
    EDITION_LABEL="CUSTOM OUTPUT ($OUT_APK)"
elif [ "$DEV_MODE" = "1" ]; then
    OUT_APK="$DIST_DIR/${BASE_PREFIX}-dev.apk"
    EDITION_LABEL="DEV EDITION (com.agy.dev, port 45158, debuggable)"
else
    OUT_APK="$DIST_DIR/${BASE_PREFIX}.apk"
    EDITION_LABEL="RELEASE EDITION (com.agy, port 45157)"
fi

if [ -f "$PROJECT_ROOT/config/tools.env" ]; then
    # shellcheck disable=SC1091
    source "$PROJECT_ROOT/config/tools.env"
fi

if [ "$DEV_MODE" = "1" ] || [ "$ALLOW_DEBUG_SIGN" = "1" ]; then
    SIGN_KEYSTORE="${DEV_KEYSTORE:-$TOOLS_DIR/debug.keystore}"
    SIGN_KEY_ALIAS="${DEV_KEY_ALIAS:-androiddebugkey}"
    SIGN_KEY_PASS="${DEV_KEY_PASS:-android}"
    SIGN_STORE_PASS="${DEV_STORE_PASS:-android}"
    if [ "$DEV_MODE" != "1" ]; then
        echo "[WARNING] Using debug keystore for non-dev build (--allow-debug-sign). Do not distribute as production release." >&2
    fi
else
    SIGN_KEYSTORE="${KEYSTORE:-}"
    SIGN_KEY_ALIAS="${KEY_ALIAS:-}"
    SIGN_KEY_PASS="${KEY_PASS:-}"
    SIGN_STORE_PASS="${STORE_PASS:-}"
    if [ -z "$SIGN_KEYSTORE" ] || [ -z "$SIGN_KEY_ALIAS" ] || [ -z "$SIGN_KEY_PASS" ] || [ -z "$SIGN_STORE_PASS" ]; then
        echo "[ERROR] Missing release signing configuration." >&2
        echo "  Set AGY_RELEASE_KEYSTORE, AGY_RELEASE_KEY_ALIAS, AGY_RELEASE_KEY_PASS, and AGY_RELEASE_STORE_PASS," >&2
        echo "  or pass --dev / --allow-debug-sign for local debug signing." >&2
        exit 1
    fi
fi

if [ ! -f "$SIGN_KEYSTORE" ]; then
    echo "[ERROR] Signing keystore not found: $SIGN_KEYSTORE" >&2
    exit 1
fi
export AGY_SIGN_KEY_PASS="$SIGN_KEY_PASS"
export AGY_SIGN_STORE_PASS="$SIGN_STORE_PASS"


echo "============================================================"
echo "  ANTIGRAVITY V2 APK BUILD PIPELINE"
echo "  $EDITION_LABEL"
if [ "$FAST_BUILD" = "1" ]; then
    echo "  [MODE: FAST ITERATION (UNCOMPRESSED ASSETS & CACHE)]"
fi
echo "============================================================"

# Check host tools
for tool in python3 aapt javac java apksigner; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "[ERROR] Missing required build tool: $tool" >&2
        exit 1
    fi
done

# Clean previous build artifacts (preserve cached runtime/env assets under --fast)
if [ "$FAST_BUILD" = "1" ]; then
    echo "[*] Cleaning build outputs (--fast preserves staged runtime/env caches)..."
    rm -rf "$BUILD_DIR/gen" "$BUILD_DIR/res" "$BUILD_DIR/obj" "$BUILD_DIR/dex" \
           "$BUILD_DIR/staging/assets/web" "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"
else
    echo "[*] Cleaning build directories..."
    rm -rf "$BUILD_DIR/gen" "$BUILD_DIR/res" "$BUILD_DIR/obj" "$BUILD_DIR/dex" "$BUILD_DIR/staging" \
           "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"
fi
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/res" "$BUILD_DIR/obj" "$BUILD_DIR/dex" "$BUILD_DIR/staging" "$DIST_DIR"

# Phase 01: Config Generation (creates BuildConfig.java, gen.xml, and build/AndroidManifest.xml)
echo -e "\n>>> [1/8] Generating Configs..."
python3 "$SCRIPT_DIR/01_gen_config.py" "$BUILD_DIR"

TARGET_MANIFEST="$BUILD_DIR/AndroidManifest.xml"

# Phase 02: Generate R.java
echo -e "\n>>> [2/8] Generating R.java..."
bash "$SCRIPT_DIR/02_r_java.sh" "$BUILD_DIR/gen" "$TARGET_MANIFEST" "$APP_DIR/src/main/res" "$TOOLS_DIR/android.jar" "$BUILD_DIR/res"

# Phase 03: Compile Java Sources
echo -e "\n>>> [3/8] Compiling Java Sources..."
bash "$SCRIPT_DIR/03_compile_java.sh" "$BUILD_DIR/obj" "$BUILD_DIR/gen" "$APP_DIR/src/main/java" "$TOOLS_DIR/android.jar"

# Phase 04: D8 Dexing
echo -e "\n>>> [4/8] Converting Bytecode to classes.dex (D8)..."
bash "$SCRIPT_DIR/04_dex.sh" "$BUILD_DIR/dex" "$BUILD_DIR/obj" "$TOOLS_DIR/r8.jar" "$TOOLS_DIR/android.jar" 24

# Phase 04b: Build Native Rust Helper (optional unless --require-native)
echo -e "\n>>> [4b/8] Building Native Rust Helper..."
bash "$SCRIPT_DIR/04b_build_native.sh"

# Phase 05: Stage Assets
echo -e "\n>>> [5/8] Staging Runtime and Web Assets..."
python3 "$SCRIPT_DIR/05_stage_assets.py" "$BUILD_DIR/staging"

# Phase 06: Single-Pass Package & ZipAlign
echo -e "\n>>> [6/8] Packaging and ZipAligning APK..."
bash "$SCRIPT_DIR/06_package.sh" "$BUILD_DIR/aligned.apk" "$TARGET_MANIFEST" "$APP_DIR/src/main/res" "$TOOLS_DIR/android.jar" "$BUILD_DIR/staging/assets" "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/res"

# Phase 07: Verify ZipAlign
echo -e "\n>>> [7/8] Verifying ZipAlign..."
if ! python3 -B "$SCRIPT_DIR/align.py" --check "$BUILD_DIR/aligned.apk" 4; then
    python3 "$SCRIPT_DIR/align.py" "$BUILD_DIR/aligned.apk" "$BUILD_DIR/unaligned.apk" 4
    mv -f "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"
fi

# Phase 08: Sign and Verify APK
echo -e "\n>>> [8/8] Signing and Verifying APK..."
bash "$SCRIPT_DIR/08_sign.sh" "$BUILD_DIR/aligned.apk" "$OUT_APK" "$SIGN_KEYSTORE" "$SIGN_KEY_ALIAS"
bash "$SCRIPT_DIR/09_verify.sh" "$OUT_APK"

echo "============================================================"
echo "  BUILD SUCCESSFUL! APK CREATED:"
echo "  $OUT_APK ($(stat -c%s "$OUT_APK") bytes)"
echo "============================================================"
