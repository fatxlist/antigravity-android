#!/usr/bin/env python3
"""
Tests for native Rust multicall binary `agy` and `04b_build_native.sh` builder phase:
- `agy version`
- Multicall dispatch via symlinks (`agy-fix-paths` and `agy-notify`)
- Byte-for-byte JSON envelope parity with Python `json.dumps` against mock HTTP gateway
- Strict POSIX single-quote `rish` fallback escaping (spaces, quotes, `$()`, backticks)
- `agy fix-paths --dry-run`
- `modules/builder/04b_build_native.sh` missing-cargo behavior with and without `--require-native`
"""

import os
import sys
sys.dont_write_bytecode = True
import json
import shutil
import subprocess
import tempfile
import threading
import unittest
import zlib
from http.server import BaseHTTPRequestHandler, HTTPServer

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_DIR = os.path.dirname(TESTS_DIR)
REPO_ROOT = os.path.dirname(ENV_DIR)
OVERLAY_BIN = os.path.join(ENV_DIR, "overlay", "bin")
NATIVE_BUILD_SH = os.path.join(REPO_ROOT, "modules", "builder", "04b_build_native.sh")


def _find_native_agy():
    for cand in (
        os.path.join(OVERLAY_BIN, "agy"),
        os.path.join(REPO_ROOT, "native", "agy", "target", "aarch64-linux-android", "release", "agy"),
        os.path.join(REPO_ROOT, "native", "agy", "target", "release", "agy"),
    ):
        if os.path.isfile(cand) and os.access(cand, os.X_OK):
            return cand
    return None


class _RawGatewayHandler(BaseHTTPRequestHandler):
    recorded = []
    expected_token = "gw_test_tok"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b""
        self.__class__.recorded.append({
            "path": self.path,
            "token": self.headers.get("X-Agy-Gateway-Token", ""),
            "content_type": self.headers.get("Content-Type", ""),
            "raw_body": raw,
        })
        if self.headers.get("X-Agy-Gateway-Token", "") != self.__class__.expected_token:
            resp = b'{"ok": false}'
            self.send_response(401)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.end_headers()
            self.wfile.write(resp)
            return
        resp = b'{"ok": true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, format, *args):
        pass


class TestNativeBuilderScript(unittest.TestCase):
    def test_missing_cargo_behavior_with_and_without_require_native(self):
        with tempfile.TemporaryDirectory(prefix="agy_no_cargo_") as tmp_dir:
            # Build minimal PATH containing standard POSIX utilities (dirname, cd, env) but NO cargo/rustc
            dummy_bin = os.path.join(tmp_dir, "bin")
            os.makedirs(dummy_bin, exist_ok=True)
            for tool in ("dirname", "head", "sed", "tr", "mkdir", "cp", "chmod", "mv", "stat"):
                src = shutil.which(tool)
                if src:
                    os.symlink(src, os.path.join(dummy_bin, tool))

            clean_env = {"PATH": dummy_bin, "HOME": tmp_dir}
            if "LD_LIBRARY_PATH" in os.environ:
                clean_env["LD_LIBRARY_PATH"] = os.environ["LD_LIBRARY_PATH"]
            bash_bin = shutil.which("bash") or "/bin/bash"

            # Without --require-native: warn and succeed (exit 0)
            res_warn = subprocess.run(
                [bash_bin, NATIVE_BUILD_SH],
                env=clean_env,
                capture_output=True,
                text=True,
                timeout=15,
            )
            self.assertEqual(res_warn.returncode, 0, res_warn.stderr)
            self.assertIn("WARNING", res_warn.stderr)

            # With --require-native: fail (exit 1)
            res_fail = subprocess.run(
                [bash_bin, NATIVE_BUILD_SH, "--require-native"],
                env=clean_env,
                capture_output=True,
                text=True,
                timeout=15,
            )
            self.assertNotEqual(res_fail.returncode, 0)
            self.assertIn("ERROR", res_fail.stderr)


class TestNativeAgyBinary(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.agy_bin = _find_native_agy()

    def setUp(self):
        if not self.agy_bin:
            self.skipTest("native agy binary not built")
        self.tmp_dir = tempfile.mkdtemp(prefix="agy_native_test_")
        self.home_dir = os.path.join(self.tmp_dir, "user0", "com.agy.dev", "files")
        self.prefix_dir = os.path.join(self.home_dir, "usr")
        os.makedirs(os.path.join(self.prefix_dir, "bin"), exist_ok=True)
        os.makedirs(os.path.join(self.home_dir, "logs"), exist_ok=True)

        _RawGatewayHandler.recorded = []
        self.server = HTTPServer(("127.0.0.1", 0), _RawGatewayHandler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        if hasattr(self, "server"):
            self.server.shutdown()
            self.server.server_close()
        if hasattr(self, "tmp_dir"):
            shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def test_version_and_multicall_symlinks(self):
        res = subprocess.run([self.agy_bin, "version"], capture_output=True, text=True, timeout=10)
        self.assertEqual(res.returncode, 0)
        self.assertTrue(res.stdout.strip().startswith("agy "))

        # Symlink agy-fix-paths -> agy
        fp_link = os.path.join(self.prefix_dir, "bin", "agy-fix-paths")
        os.symlink(self.agy_bin, fp_link)
        target_file = os.path.join(self.prefix_dir, "demo.sh")
        with open(target_file, "w", encoding="utf-8") as f:
            f.write("#!/data/data/com.termux/files/usr/bin/sh\n")

        res_fp = subprocess.run(
            [fp_link, "--dry-run", target_file],
            env={"PREFIX": self.prefix_dir, "HOME": self.home_dir, "PATH": os.environ.get("PATH", "")},
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(res_fp.returncode, 0, res_fp.stderr)
        self.assertIn("would rewrite 1 file(s)", res_fp.stdout)
        with open(target_file, "r", encoding="utf-8") as f:
            self.assertIn("com.termux", f.read(), "--dry-run must not modify file")

        # Symlink agy-notify -> agy
        notify_link = os.path.join(self.prefix_dir, "bin", "agy-notify")
        os.symlink(self.agy_bin, notify_link)
        res_n = subprocess.run(
            [notify_link, "list"],
            env={"PREFIX": self.prefix_dir, "HOME": self.home_dir, "PATH": os.environ.get("PATH", "")},
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(res_n.returncode, 0)
        self.assertIn("rish unavailable", res_n.stdout)

    def test_notify_json_envelope_byte_for_byte_parity_and_rish_fallback(self):
        with open(os.path.join(self.home_dir, "logs", "gateway.port"), "w", encoding="utf-8") as f:
            f.write(f"{self.port}\n")
        with open(os.path.join(self.home_dir, "logs", "gateway.token"), "w", encoding="utf-8") as f:
            f.write(f"{_RawGatewayHandler.expected_token}\n")

        title = 'Agent "Done"'
        subtitle = "Sub"
        message = "Привет 😀 'ok'"
        tag = "turn_parity_1"
        channel = "agy_alerts"
        cascade_id = "conv-99"

        clean_env = {
            "PREFIX": self.prefix_dir,
            "HOME": self.home_dir,
            "PATH": os.environ.get("PATH", ""),
        }

        res = subprocess.run(
            [
                self.agy_bin,
                "notify",
                "post",
                "-t", title,
                "-s", subtitle,
                "-m", message,
                "--tag", tag,
                "-c", channel,
                "--cascade-id", cascade_id,
            ],
            env=clean_env,
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(len(_RawGatewayHandler.recorded), 1)
        req = _RawGatewayHandler.recorded[0]
        self.assertEqual(req["path"], "/gateway")
        self.assertEqual(req["token"], _RawGatewayHandler.expected_token)

        stable_id = (zlib.crc32(tag.encode("utf-8")) & 0x7FFFFFFF) or 1
        expected_envelope = {
            "command": "notify",
            "payload": {
                "action": "post",
                "tag": tag,
                "id": stable_id,
                "title": title,
                "message": f"[{subtitle}] {message}",
                "channel": channel,
                "cascade_id": cascade_id,
            },
        }
        expected_bytes = json.dumps(expected_envelope).encode("utf-8")
        self.assertEqual(req["raw_body"], expected_bytes)

        # Remove token and test strict POSIX single-quoted rish fallback against injection payload
        os.remove(os.path.join(self.home_dir, "logs", "gateway.token"))
        pwn_file = os.path.join(self.tmp_dir, "PWNED")
        rish_bin = os.path.join(self.prefix_dir, "bin", "rish")
        capture_log = os.path.join(self.tmp_dir, "rish_calls.log")
        with open(rish_bin, "w", encoding="utf-8") as f:
            f.write("#!/system/bin/sh\necho \"$*\" >> \"$AGY_RISH_LOG\"\neval \"$2\" >/dev/null 2>&1 || true\n")
        os.chmod(rish_bin, 0o755)

        evil_msg = f"hi'; touch '{pwn_file}'; $(touch '{pwn_file}') `touch '{pwn_file}'`"
        res_fb = subprocess.run(
            [self.agy_bin, "notify", "post", "-t", "T'1", "-m", evil_msg, "--tag", "tag_1"],
            env={**clean_env, "AGY_RISH_LOG": capture_log},
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(res_fb.returncode, 0, res_fb.stderr)
        self.assertFalse(os.path.exists(pwn_file), "Strict POSIX single quoting must prevent shell injection")
        with open(capture_log, "r", encoding="utf-8") as f:
            logged = f.read()
        self.assertIn("cmd notification post -S bigtext", logged)
        self.assertIn("'T'\\''1'", logged)


if __name__ == "__main__":
    unittest.main()
