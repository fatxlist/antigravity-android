#!/usr/bin/env python3
"""
Security and file-permission tests for Stage 1 hardening:
- agy-daemon enforces os.umask(0o077) and explicit 0600 modes on tasks.json, daemon.log, daemon.pid, daemon.lock
- GitHub and webhook tokens are never written to daemon.log
- agy-upgrade stores backups in private $HOME/backups (0700 dir, 0600 archive),
  creates a real latest.tar.gz symlink, and excludes tokens, vaults, daemon runtime files, and .bash_history
"""

import os
import sys
sys.dont_write_bytecode = True
import importlib.machinery
import importlib.util
import json
import shutil
import socket
import stat
import subprocess
import tarfile
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest import mock

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_DIR = os.path.dirname(TESTS_DIR)
OVERLAY_BIN = os.path.join(ENV_DIR, "overlay", "bin")
OVERLAY_LIB = os.path.join(ENV_DIR, "overlay", "lib")
if OVERLAY_LIB not in sys.path:
    sys.path.insert(0, OVERLAY_LIB)

from daemon import executor, triggers  # noqa: E402


def _load_agy_daemon_module():
    daemon_path = os.path.join(OVERLAY_BIN, "agy-daemon")
    loader = importlib.machinery.SourceFileLoader("agy_daemon_cli_test", daemon_path)
    spec = importlib.util.spec_from_loader("agy_daemon_cli_test", loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


class TestSecurityAndPermissions(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp(prefix="agy_sec_test_")
        self.home_dir = os.path.join(self.tmp_dir, "user0", "com.agy.dev", "files")
        self.prefix_dir = os.path.join(self.home_dir, "usr")
        os.makedirs(os.path.join(self.prefix_dir, "bin"), exist_ok=True)
        os.makedirs(os.path.join(self.home_dir, "logs"), exist_ok=True)

        self._orig = {
            "PREFIX": executor.PREFIX,
            "HOME": executor.HOME,
            "CONFIG_DIR": executor.CONFIG_DIR,
            "TASKS_FILE": executor.TASKS_FILE,
            "LOG_FILE": executor.LOG_FILE,
            "PID_FILE": executor.PID_FILE,
            "LOCK_FILE": executor.LOCK_FILE,
        }
        executor.PREFIX = self.prefix_dir
        executor.HOME = self.home_dir
        executor.CONFIG_DIR = os.path.join(self.home_dir, ".gemini", "antigravity-app", "daemon")
        executor.TASKS_FILE = os.path.join(executor.CONFIG_DIR, "tasks.json")
        executor.LOG_FILE = os.path.join(executor.CONFIG_DIR, "daemon.log")
        executor.PID_FILE = os.path.join(executor.CONFIG_DIR, "daemon.pid")
        executor.LOCK_FILE = os.path.join(executor.CONFIG_DIR, "daemon.lock")

    def tearDown(self):
        for k, v in self._orig.items():
            setattr(executor, k, v)
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def test_daemon_uses_strict_umask_077(self):
        daemon_path = os.path.join(OVERLAY_BIN, "agy-daemon")
        with open(daemon_path, "r", encoding="utf-8") as f:
            src = f.read()
        self.assertIn("os.umask(0o077)", src)
        self.assertNotIn("os.umask(0)", src)

    def test_daemon_files_enforced_0600_under_permissive_umask(self):
        daemon_mod = _load_agy_daemon_module()
        daemon_mod.CONFIG_DIR = executor.CONFIG_DIR
        daemon_mod.PID_FILE = executor.PID_FILE
        daemon_mod.LOCK_FILE = executor.LOCK_FILE

        old_umask = os.umask(0o000)
        try:
            executor.save_tasks([{"id": "t1", "trigger": {"type": "webhook", "token": "secret_tok"}}])
            executor.log("permission check line")
            daemon_mod.write_pid_file()
            fd = daemon_mod.acquire_daemon_lock()
            self.assertIsNotNone(fd)
            os.close(fd)
            daemon_mod._LOCK_FD = None

            self.assertEqual(stat.S_IMODE(os.stat(executor.CONFIG_DIR).st_mode), 0o700)
            for fp in (executor.TASKS_FILE, executor.LOG_FILE, executor.PID_FILE, executor.LOCK_FILE):
                self.assertEqual(stat.S_IMODE(os.stat(fp).st_mode), 0o600, f"{fp} should have mode 0600")

            # Pre-existing permissive 0666 files must be tightened back to 0600 on next write
            os.chmod(executor.TASKS_FILE, 0o666)
            os.chmod(executor.LOG_FILE, 0o666)
            executor.save_tasks([{"id": "t2"}])
            executor.log("tighten check")
            self.assertEqual(stat.S_IMODE(os.stat(executor.TASKS_FILE).st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(os.stat(executor.LOG_FILE).st_mode), 0o600)
        finally:
            os.umask(old_umask)

    def test_github_and_webhook_tokens_never_logged(self):
        gh_env_secret = "ghp_FakeEnvSecretToken1234567890"
        gh_task_secret = "github_pat_FakeTaskSecretTokenABCDEF"
        wh_secret = "wh_super_secret_token_987654321"

        # 1. GitHub trigger error embedding token in exception message
        def fake_urlopen(req, timeout=15):
            raise RuntimeError(f"HTTP failure with Authorization: token {req.headers.get('Authorization', '')} {gh_env_secret}")

        with mock.patch.dict(os.environ, {"GITHUB_TOKEN": gh_env_secret}):
            with mock.patch.object(triggers.urllib.request, "urlopen", side_effect=fake_urlopen):
                triggers.check_github_repo({"repo": "owner/repo"}, "pulls")

        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("GITHUB_TOKEN", None)
            with mock.patch.object(triggers.urllib.request, "urlopen", side_effect=fake_urlopen):
                triggers.check_github_repo({"repo": "owner/repo", "token": gh_task_secret}, "issues")

        # 2. Live webhook listener receiving authenticated & unauthenticated requests
        executor.save_tasks([
            {
                "id": "wh_task_1",
                "name": "Webhook Test",
                "command": "",
                "prompt": "",
                "enabled": True,
                "trigger": {"type": "webhook", "port": 0, "token": wh_secret},
            }
        ])

        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
        probe.close()

        tasks = executor.load_tasks()
        tasks[0]["trigger"]["port"] = port
        executor.save_tasks(tasks)

        t = threading.Thread(target=triggers.start_webhook_listener, args=(port,), daemon=True)
        t.start()

        deadline = time.time() + 3.0
        while time.time() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                    break
            except OSError:
                time.sleep(0.05)

        with mock.patch.object(triggers, "execute_action_async") as mock_exec:
            # Unauthorized request -> 401
            bad_req = urllib.request.Request(
                f"http://127.0.0.1:{port}/",
                data=b'{"event":"test"}',
                headers={"Content-Type": "application/json", "X-AGY-Token": "wrong"},
                method="POST",
            )
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(bad_req, timeout=2)
            self.assertEqual(ctx.exception.code, 401)
            ctx.exception.close()

            # Authorized request whose body echoes the token -> 200, token masked in log
            ok_req = urllib.request.Request(
                f"http://127.0.0.1:{port}/",
                data=json.dumps({"echo": wh_secret, "ok": True}).encode("utf-8"),
                headers={"Content-Type": "application/json", "X-AGY-Token": wh_secret},
                method="POST",
            )
            with urllib.request.urlopen(ok_req, timeout=2) as resp:
                self.assertEqual(resp.status, 200)
                body = json.loads(resp.read().decode("utf-8"))
                self.assertEqual(body.get("fired"), 1)
            mock_exec.assert_called_once()

        with open(executor.LOG_FILE, "r", encoding="utf-8") as f:
            log_text = f.read()

        self.assertIn("GitHub check error", log_text)
        self.assertIn("Authenticated webhook on port", log_text)
        self.assertNotIn(gh_env_secret, log_text)
        self.assertNotIn(gh_task_secret, log_text)
        self.assertNotIn(wh_secret, log_text)

    def test_agy_upgrade_private_backup_exclusions_and_symlink(self):
        # Mock pkg & agy-fix-paths so backup/restore stay completely offline and fast
        mock_pkg = os.path.join(self.prefix_dir, "bin", "pkg")
        with open(mock_pkg, "w", encoding="utf-8") as f:
            f.write("#!/bin/sh\nif [ \"$1\" = \"installed\" ]; then echo 'git 2.0'; fi\n")
        os.chmod(mock_pkg, 0o755)

        mock_fix = os.path.join(self.prefix_dir, "bin", "agy-fix-paths")
        with open(mock_fix, "w", encoding="utf-8") as f:
            f.write("#!/bin/sh\nexit 0\n")
        os.chmod(mock_fix, 0o755)

        # Populate .gemini (mix of regular user state and sensitive files) + shell files
        gemini_dir = os.path.join(self.home_dir, ".gemini")
        brain_dir = os.path.join(gemini_dir, "antigravity-app", "brain", "conv-1")
        daemon_dir = os.path.join(gemini_dir, "antigravity-app", "daemon")
        cfg_dir = os.path.join(gemini_dir, "config")
        os.makedirs(brain_dir, exist_ok=True)
        os.makedirs(daemon_dir, exist_ok=True)
        os.makedirs(cfg_dir, exist_ok=True)

        files_to_seed = {
            os.path.join(gemini_dir, "jetski-standalone-oauth-token"): "SECRET_OAUTH_TOKEN\n",
            os.path.join(gemini_dir, "agy-account-vault.json"): '{"token":"SECRET_VAULT"}\n',
            os.path.join(daemon_dir, "tasks.json"): '[{"trigger":{"token":"SECRET_WEBHOOK"}}]\n',
            os.path.join(daemon_dir, "hub_12345.json"): '{"token":"SECRET_HUB"}\n',
            os.path.join(daemon_dir, "daemon.log"): "daemon log\n",
            os.path.join(brain_dir, "notes.md"): "# Brain Notes\n",
            os.path.join(cfg_dir, "AGENTS.md"): "# Custom Rules\n",
            os.path.join(cfg_dir, "mcp_config.json"): '{"mcpServers":{}}\n',
            os.path.join(self.home_dir, ".bashrc"): "export FOO=bar\n",
            os.path.join(self.home_dir, ".bash_history"): "curl -H 'Authorization: Bearer SECRET_CMD'\n",
        }
        for fp, content in files_to_seed.items():
            with open(fp, "w", encoding="utf-8") as f:
                f.write(content)

        upgrade_bin = os.path.join(OVERLAY_BIN, "agy-upgrade")
        clean_env = dict(os.environ)
        clean_env["HOME"] = self.home_dir
        clean_env["PREFIX"] = self.prefix_dir
        clean_env["PATH"] = f"{os.path.join(self.prefix_dir, 'bin')}:{clean_env.get('PATH', '')}"
        clean_env.pop("AGY_BACKUP_DIR", None)

        res = subprocess.run(
            ["bash", upgrade_bin, "backup"],
            env=clean_env,
            capture_output=True,
            text=True,
            timeout=20,
        )
        self.assertEqual(res.returncode, 0, f"agy-upgrade backup failed:\n{res.stdout}\n{res.stderr}")

        backup_dir = os.path.join(self.home_dir, "backups")
        self.assertTrue(os.path.isdir(backup_dir))
        self.assertEqual(stat.S_IMODE(os.stat(backup_dir).st_mode), 0o700)

        latest_link = os.path.join(backup_dir, "latest.tar.gz")
        self.assertTrue(os.path.islink(latest_link), "latest.tar.gz must be a real symlink")
        resolved_archive = os.path.realpath(latest_link)
        self.assertTrue(os.path.isfile(resolved_archive))
        self.assertEqual(stat.S_IMODE(os.stat(resolved_archive).st_mode), 0o600)

        with tarfile.open(resolved_archive, "r:gz") as tf:
            names = tf.getnames()

        self.assertIn(".gemini/antigravity-app/brain/conv-1/notes.md", names)
        self.assertIn(".gemini/config/AGENTS.md", names)
        self.assertIn(".gemini/config/mcp_config.json", names)
        self.assertIn(".bashrc", names)

        for forbidden in (
            ".bash_history",
            ".gemini/jetski-standalone-oauth-token",
            ".gemini/agy-account-vault.json",
            ".gemini/antigravity-app/daemon/tasks.json",
            ".gemini/antigravity-app/daemon/hub_12345.json",
            ".gemini/antigravity-app/daemon/daemon.log",
        ):
            self.assertNotIn(forbidden, names, f"Sensitive file leaked into backup: {forbidden}")

        # Verify restore into a clean HOME using latest.tar.gz symlink
        restore_home = os.path.join(self.tmp_dir, "restored_home")
        restore_prefix = os.path.join(restore_home, "usr")
        os.makedirs(os.path.join(restore_prefix, "bin"), exist_ok=True)
        shutil.copy2(mock_pkg, os.path.join(restore_prefix, "bin", "pkg"))
        shutil.copy2(mock_fix, os.path.join(restore_prefix, "bin", "agy-fix-paths"))

        restore_env = dict(clean_env)
        restore_env["HOME"] = restore_home
        restore_env["PREFIX"] = restore_prefix
        restore_env["PATH"] = f"{os.path.join(restore_prefix, 'bin')}:{os.environ.get('PATH', '')}"

        res_restore = subprocess.run(
            ["bash", upgrade_bin, "restore", latest_link],
            env=restore_env,
            capture_output=True,
            text=True,
            timeout=20,
        )
        self.assertEqual(res_restore.returncode, 0, f"agy-upgrade restore failed:\n{res_restore.stdout}\n{res_restore.stderr}")
        self.assertTrue(os.path.isfile(os.path.join(restore_home, ".gemini", "antigravity-app", "brain", "conv-1", "notes.md")))
        self.assertTrue(os.path.isfile(os.path.join(restore_home, ".bashrc")))
        self.assertFalse(os.path.exists(os.path.join(restore_home, ".bash_history")))
        self.assertFalse(os.path.exists(os.path.join(restore_home, ".gemini", "jetski-standalone-oauth-token")))


if __name__ == "__main__":
    unittest.main()
