#!/usr/bin/env python3
"""
05_stage_assets.py - Stages runtime, web assets, and manifests into build/staging/assets.
Supports incremental reuse when FAST_BUILD=1 (or --fast).
"""
import json
import os
import shutil
import subprocess
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
MODULES_DIR = os.path.join(PROJECT_ROOT, "modules")
if MODULES_DIR not in sys.path:
    sys.path.insert(0, MODULES_DIR)

sys.path.insert(0, os.path.join(MODULES_DIR, "core", "manifest"))
import manifest_verifier  # noqa: E402
from runner import prepare_runtime  # noqa: E402


def _latest_mtime(path):
    if not os.path.exists(path):
        return 0.0
    if os.path.isfile(path):
        return os.path.getmtime(path)
    latest = os.path.getmtime(path)
    for root, _, files in os.walk(path):
        if "__pycache__" in root:
            continue
        for fname in files:
            if fname.endswith((".pyc", ".pyo")):
                continue
            full = os.path.join(root, fname)
            try:
                mt = os.path.getmtime(full)
                if mt > latest:
                    latest = mt
            except OSError:
                pass
    return latest


def can_reuse_staged_runtime(runtime_dst, project_root=PROJECT_ROOT):
    manifest_path = os.path.join(runtime_dst, "manifest.json")
    if not os.path.isfile(manifest_path):
        return False
    try:
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        core_sha = manifest.get("files", {}).get("bin/liblanguage_server.so", "").lower()
        if core_sha != prepare_runtime.TARGET_SHA.lower():
            return False
        manifest_mtime = os.path.getmtime(manifest_path)
        for rel in ("shims", "certs", "seed", "glibc"):
            src_dir = os.path.join(project_root, "modules", "core", rel)
            if _latest_mtime(src_dir) > manifest_mtime:
                return False
        return bool(manifest_verifier.verify_manifest(manifest_path, runtime_dst))
    except Exception:
        return False


def env_assets_need_rebuild(project_root=PROJECT_ROOT):
    env_root = os.path.join(project_root, "env")
    env_dist = os.path.join(env_root, "dist", "env")
    manifest_file = os.path.join(env_dist, "manifest.json")
    if not os.path.isfile(manifest_file):
        return True
    manifest_mtime = os.path.getmtime(manifest_file)
    for rel in ("pkg", "overlay", os.path.join("bootstrap", "bootstrap-aarch64.zip"), "seed-packages.txt", "packages"):
        if _latest_mtime(os.path.join(env_root, rel)) > manifest_mtime:
            return True
    res = subprocess.run(
        [sys.executable, "-B", os.path.join(env_root, "scripts", "make_manifest.py"), "--check", env_dist],
        capture_output=True,
        text=True,
    )
    return res.returncode != 0


def can_reuse_staged_env(env_src, env_dst):
    src_manifest = os.path.join(env_src, "manifest.json")
    dst_manifest = os.path.join(env_dst, "manifest.json")
    dst_tar = os.path.join(env_dst, "bootstrap", "bootstrap.tar.gz")
    if not (os.path.isfile(src_manifest) and os.path.isfile(dst_manifest) and os.path.isfile(dst_tar)):
        return False
    try:
        with open(src_manifest, "rb") as fs, open(dst_manifest, "rb") as fd:
            return fs.read() == fd.read()
    except OSError:
        return False


def stage_assets(staging_dir, fast_build=False):
    assets_dir = os.path.join(staging_dir, "assets")
    runtime_dst = os.path.join(assets_dir, "runtime")
    web_dst = os.path.join(assets_dir, "web")
    os.makedirs(assets_dir, exist_ok=True)

    if fast_build and can_reuse_staged_runtime(runtime_dst, PROJECT_ROOT):
        print(f"[FAST] Reusing verified staged runtime assets -> {runtime_dst}")
    else:
        print(f"[*] Staging fully patched runtime assets -> {runtime_dst}...")
        ok = prepare_runtime.prepare(dest_runtime_dir=runtime_dst)
        if not ok:
            raise RuntimeError("Failed to prepare and stage runtime assets!")

    web_src = os.path.join(PROJECT_ROOT, "web")
    if os.path.isdir(web_src):
        web_build_script = os.path.join(web_src, "build.sh")
        if os.path.isfile(web_build_script):
            print(f"[*] Building web bundles via {web_build_script}...")
            subprocess.check_call(["bash", web_build_script], cwd=PROJECT_ROOT)

        print(f"[*] Staging web assets from {web_src} -> {web_dst}...")
        if os.path.exists(web_dst):
            shutil.rmtree(web_dst)

        def web_ignore(directory, files):
            ignored = set()
            rel_dir = os.path.relpath(directory, web_src)
            if rel_dir == ".":
                for name in ("main.js", "src", "tests", "build.sh", "patch_main.py", "src.manifest"):
                    if name in files:
                        ignored.add(name)
            return ignored

        shutil.copytree(web_src, web_dst, ignore=web_ignore)
        print(f"[OK] Staged web assets.")

    env_src = os.path.join(PROJECT_ROOT, "env", "dist", "env")
    env_dst = os.path.join(assets_dir, "env")
    if env_assets_need_rebuild(PROJECT_ROOT):
        print(f"[*] env manifest missing or stale, rebuilding env assets...")
        subprocess.check_call(["bash", os.path.join(PROJECT_ROOT, "env", "build.sh"), "assets"])
    if fast_build and can_reuse_staged_env(env_src, env_dst):
        print(f"[FAST] Reusing verified staged env assets -> {env_dst}")
    else:
        print(f"[*] Staging env assets from {env_src} -> {env_dst}...")
        if os.path.exists(env_dst):
            shutil.rmtree(env_dst)
        shutil.copytree(env_src, env_dst)
        print(f"[OK] Staged env assets.")

    print(f"[OK] Staged all assets into {assets_dir}")
    return assets_dir


def main():
    fast_build = os.environ.get("FAST_BUILD") == "1"
    staging_dir = os.path.join(PROJECT_ROOT, "build", "staging")
    for arg in sys.argv[1:]:
        if arg == "--fast":
            fast_build = True
        elif not arg.startswith("-"):
            staging_dir = arg
    stage_assets(staging_dir, fast_build=fast_build)


if __name__ == "__main__":
    main()
