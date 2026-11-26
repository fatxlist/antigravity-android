#!/usr/bin/env python3
"""
check_versions.py - Verify version consistency across config/app.env, READMEs,
RELEASE_NOTES.md, and optional CI tag reference (GITHUB_REF).
"""
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))


def parse_env(path: str) -> dict:
    """Parse plain KEY=VALUE lines from an env file, skipping comments."""
    data = {}
    with open(path, "r", encoding="utf-8") as f:
        for raw_line in f:
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            data[key.strip()] = value.strip().strip('"').strip("'")
    return data


def read_text(path: str) -> str:
    """Read a UTF-8 text file relative to PROJECT_ROOT."""
    with open(os.path.join(PROJECT_ROOT, path), "r", encoding="utf-8") as f:
        return f.read()


def main() -> int:
    env_path = os.path.join(PROJECT_ROOT, "config", "app.env")
    if not os.path.isfile(env_path):
        print(f"[FAIL] Missing config file: {env_path}", file=sys.stderr)
        return 1

    env = parse_env(env_path)
    app_ver = env.get("APP_VERSION_NAME", "").strip()
    core_ver = env.get("CORE_VERSION", "").strip()

    errors = []
    if not app_ver:
        errors.append("config/app.env: APP_VERSION_NAME is missing or empty")
    if not core_ver:
        errors.append("config/app.env: CORE_VERSION is missing or empty")

    if not errors:
        checks = [
            ("README.md", f"badge/App-v{app_ver}-", "app badge"),
            ("README.md", f"badge/Core-v{core_ver}-", "core badge"),
            ("README.ru.md", f"badge/Версия-{app_ver}-", "app badge"),
            ("README.ru.md", f"badge/Ядро-v{core_ver}-", "core badge"),
        ]
        for rel_path, needle, label in checks:
            full_path = os.path.join(PROJECT_ROOT, rel_path)
            if not os.path.isfile(full_path):
                errors.append(f"Missing file: {rel_path}")
                continue
            content = read_text(rel_path)
            if needle not in content:
                errors.append(f"{rel_path}: {label} missing expected token '{needle}'")

        release_notes_path = os.path.join(PROJECT_ROOT, "RELEASE_NOTES.md")
        if not os.path.isfile(release_notes_path):
            errors.append("Missing file: RELEASE_NOTES.md")
        else:
            notes = read_text("RELEASE_NOTES.md")
            first_line = notes.splitlines()[0] if notes else ""
            if f"v{app_ver}" not in first_line:
                errors.append(
                    f"RELEASE_NOTES.md: first line '{first_line}' does not contain 'v{app_ver}'"
                )

        github_ref = os.environ.get("GITHUB_REF", "")
        if github_ref.startswith("refs/tags/v"):
            tag = github_ref[len("refs/tags/"):]
            expected_tag = f"v{app_ver}"
            if tag != expected_tag:
                errors.append(
                    f"GITHUB_REF tag '{tag}' does not match config/app.env '{expected_tag}'"
                )

    if errors:
        for err in errors:
            print(f"[FAIL] {err}", file=sys.stderr)
        return 1

    print(f"[OK] versions consistent: app {app_ver}, core {core_ver}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
