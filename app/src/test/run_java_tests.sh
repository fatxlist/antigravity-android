#!/usr/bin/env bash
# run_java_tests.sh - Compiles Java sources + host JVM unit tests against android.jar and runs them.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
TOOLS_DIR="$PROJECT_ROOT/tools"
ANDROID_JAR="$TOOLS_DIR/android.jar"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "[ERROR] Missing android.jar at $ANDROID_JAR" >&2
    exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

python3 -B "$PROJECT_ROOT/modules/builder/01_gen_config.py" "$WORK_DIR" >/dev/null

# Generate minimal R.java stub covering all R.<type>.<name> references in app/src/main/java.
python3 -B - "$PROJECT_ROOT/app/src/main/java" "$WORK_DIR/gen/com/agy/R.java" << 'PYEOF'
import collections, os, re, sys

src_root, out_path = sys.argv[1], sys.argv[2]
refs = collections.defaultdict(set)
for root, _, files in os.walk(src_root):
    for fn in files:
        if fn.endswith(".java"):
            with open(os.path.join(root, fn), encoding="utf-8") as f:
                txt = f.read()
            for t, n in re.findall(r"\bR\.([a-z]+)\.([a-zA-Z0-9_]+)\b", txt):
                refs[t].add(n)

os.makedirs(os.path.dirname(out_path), exist_ok=True)
with open(out_path, "w", encoding="utf-8") as f:
    f.write("package com.agy;\npublic final class R {\n")
    idx = 0x7f010000
    for cls, names in sorted(refs.items()):
        f.write(f"  public static final class {cls} {{\n")
        for n in sorted(names):
            idx += 1
            f.write(f"    public static final int {n} = {idx};\n")
        f.write("  }\n")
    f.write("}\n")
PYEOF

find "$WORK_DIR/gen" "$PROJECT_ROOT/app/src/main/java" "$SCRIPT_DIR/java" \
    -name "*.java" > "$WORK_DIR/sources.txt"

mkdir -p "$WORK_DIR/classes"
javac -source 8 -target 8 -encoding UTF-8 -Xlint:all,-deprecation,-options,-serial -Werror \
    -cp "$ANDROID_JAR" \
    -d "$WORK_DIR/classes" \
    "@$WORK_DIR/sources.txt"

java -cp "$WORK_DIR/classes:$ANDROID_JAR" com.agy.UnitTests
