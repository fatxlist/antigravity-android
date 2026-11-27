#!/usr/bin/env bash
# 04b_build_native.sh - Compile native Rust multicall helper (native/agy) for aarch64-linux-android.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${PROJECT_ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
MANIFEST_PATH="$PROJECT_ROOT/native/agy/Cargo.toml"
OUT_BIN="$PROJECT_ROOT/env/overlay/bin/agy"
REQUIRE_NATIVE="${REQUIRE_NATIVE:-0}"

for arg in "$@"; do
    case "$arg" in
        --require-native)
            REQUIRE_NATIVE=1
            ;;
    esac
done

fail_or_warn() {
    local msg="$1"
    if [ "$REQUIRE_NATIVE" = "1" ]; then
        echo "[ERROR] $msg" >&2
        exit 1
    fi
    echo "[WARNING] $msg; falling back to bash/Python scripts." >&2
    exit 0
}

if [ ! -f "$MANIFEST_PATH" ]; then
    fail_or_warn "Native manifest missing: $MANIFEST_PATH"
fi

if ! command -v cargo >/dev/null 2>&1 || ! command -v rustc >/dev/null 2>&1; then
    fail_or_warn "Rust toolchain (cargo/rustc) not found"
fi

TARGET="aarch64-linux-android"
HOST_TRIPLE="$(rustc -vV 2>/dev/null | sed -n 's/^host: //p' | head -n 1 || true)"

if [ "$HOST_TRIPLE" != "$TARGET" ]; then
    if ! rustc --print target-libdir --target "$TARGET" >/dev/null 2>&1; then
        fail_or_warn "Rust target '$TARGET' stdlib not installed (run: rustup target add $TARGET)"
    fi
fi

find_ndk_clang() {
    if command -v aarch64-linux-android24-clang >/dev/null 2>&1; then
        command -v aarch64-linux-android24-clang
        return 0
    fi
    local root
    for root in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_LATEST_HOME:-}" "${ANDROID_NDK_ROOT:-}" "${NDK_HOME:-}"; do
        [ -n "$root" ] && [ -d "$root" ] || continue
        local cand
        for cand in "$root"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android24-clang; do
            if [ -x "$cand" ]; then
                printf '%s' "$cand"
                return 0
            fi
        done
    done
    return 1
}

if [ "$HOST_TRIPLE" = "$TARGET" ]; then
    # On-device native Bionic build: use local clang directly without NDK.
    CC_BIN="${CC:-$(command -v clang 2>/dev/null || true)}"
    [ -n "$CC_BIN" ] && [ -x "$CC_BIN" ] || fail_or_warn "On-device clang linker not found"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_BIN"
    echo "[*] Building native agy on-device ($TARGET, linker=$CC_BIN)..."
else
    NDK_CLANG="$(find_ndk_clang || true)"
    [ -n "$NDK_CLANG" ] || fail_or_warn "Android NDK linker aarch64-linux-android24-clang not found"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_CLANG"
    echo "[*] Cross-compiling native agy ($HOST_TRIPLE -> $TARGET, linker=$NDK_CLANG)..."
fi

export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"

cargo build --release --manifest-path "$MANIFEST_PATH" --target "$TARGET"

BUILT_BIN="$PROJECT_ROOT/native/agy/target/$TARGET/release/agy"
if [ ! -f "$BUILT_BIN" ]; then
    fail_or_warn "Expected compiled binary missing: $BUILT_BIN"
fi

mkdir -p "$(dirname "$OUT_BIN")"
cp -f "$BUILT_BIN" "$OUT_BIN.tmp"
chmod 0755 "$OUT_BIN.tmp"
mv -f "$OUT_BIN.tmp" "$OUT_BIN"
echo "[OK] Installed native binary -> $OUT_BIN ($(stat -c%s "$OUT_BIN") bytes)"
