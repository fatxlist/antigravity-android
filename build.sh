#!/usr/bin/env bash
# build.sh - Convenient root wrapper for Antigravity APK builder.
# Usage:
#   bash build.sh                    # Build release edition (dist/antigravity.apk; requires AGY_RELEASE_* signing env)
#   bash build.sh --dev              # Build debuggable dev edition (dist/antigravity-dev.apk)
#   bash build.sh --allow-debug-sign # Build release package signed with debug keystore for local testing
#   bash build.sh --fast             # Fast uncompressed assets iteration

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$SCRIPT_DIR/modules/builder/build_apk.sh" "$@"
