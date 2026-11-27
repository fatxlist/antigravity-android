#!/usr/bin/env python3
"""
Unit tests for env/overlay/lib/daemon/executor.py:
- Dynamic PREFIX / HOME resolution (no hardcoded /data/user/0/com.agy/files)
- Shell-injection / quote safety when prompt contains dangerous metacharacters
- Real ConnectRPC AI execution (StartCascade + SendUserCascadeMessage) against a mock server
- Direct SendUserCascadeMessage when conversation_id is already provided
- Safe fallback notification via argv list when core.state.json is missing or server unreachable
- Shell command execution uses dynamic HOME / PREFIX / cwd
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
from unittest import mock
from http.server import BaseHTTPRequestHandler, HTTPServer

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_DIR = os.path.dirname(TESTS_DIR)
OVERLAY_LIB = os.path.join(ENV_DIR, "overlay", "lib")
if OVERLAY_LIB not in sys.path:
    sys.path.insert(0, OVERLAY_LIB)

from daemon import executor, scheduler  # noqa: E402


class _MockConnectRpcHandler(BaseHTTPRequestHandler):
    recorded_requests = []
    start_cascade_id = "cascade-test-999"
    fail_mode = False

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(length).decode("utf-8", errors="replace") if length else ""
        try:
            parsed = json.loads(raw_body) if raw_body else {}
        except json.JSONDecodeError:
            parsed = {"_raw": raw_body}

        self.__class__.recorded_requests.append({
            "path": self.path,
            "csrf": self.headers.get("x-codeium-csrf-token", ""),
            "gateway_token": self.headers.get("X-Agy-Gateway-Token", ""),
            "connect_version": self.headers.get("Connect-Protocol-Version", ""),
            "content_type": self.headers.get("Content-Type", ""),
            "body": parsed,
        })

        if self.__class__.fail_mode:
            resp = json.dumps({"code": "unavailable", "message": "mock core down"}).encode("utf-8")
            self.send_response(503)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.end_headers()
            self.wfile.write(resp)
            return

        if self.path.endswith("/StartCascade"):
            payload = {"cascadeId": self.__class__.start_cascade_id}
        elif self.path.endswith("/SendUserCascadeMessage"):
            payload = {}
        elif self.path == "/gateway":
            payload = {"ok": True}
        else:
            self.send_response(404)
            self.end_headers()
            return

        resp = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, format, *args):
        pass


class TestDaemonExecutor(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp(prefix="agy_exec_test_")
        # Simulate multi-user / dev package layout (NOT /data/user/0/com.agy/files)
        self.home_dir = os.path.join(self.tmp_dir, "user10", "com.agy.dev", "files")
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
            "BASH_BIN": executor.BASH_BIN,
            "RISH_BIN": executor.RISH_BIN,
            "AGY_APPLICATION_ID": executor.AGY_APPLICATION_ID,
        }
        executor.PREFIX = self.prefix_dir
        executor.HOME = self.home_dir
        executor.CONFIG_DIR = os.path.join(self.home_dir, ".gemini", "antigravity-app", "daemon")
        executor.TASKS_FILE = os.path.join(executor.CONFIG_DIR, "tasks.json")
        executor.LOG_FILE = os.path.join(executor.CONFIG_DIR, "daemon.log")
        executor.PID_FILE = os.path.join(executor.CONFIG_DIR, "daemon.pid")
        executor.BASH_BIN = os.path.join(self.prefix_dir, "bin", "bash")
        executor.RISH_BIN = os.path.join(self.prefix_dir, "bin", "rish")
        executor.AGY_APPLICATION_ID = executor.application_id_from_prefix(self.prefix_dir)

        _MockConnectRpcHandler.recorded_requests = []
        _MockConnectRpcHandler.fail_mode = False
        self.server = HTTPServer(("127.0.0.1", 0), _MockConnectRpcHandler)
        self.port = self.server.server_address[1]
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        for k, v in self._orig.items():
            setattr(executor, k, v)
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def _write_core_state(self, ready=True, csrf="test-csrf-secret"):
        state = {
            "ready": ready,
            "https_port": self.port,
            "csrf_token": csrf,
            "base_url": f"http://127.0.0.1:{self.port}",
            "gateway_port": 45160,
            "pid": 12345,
            "ready_at": 1700000000000,
        }
        path = executor.get_core_state_path()
        with open(path, "w", encoding="utf-8") as f:
            json.dump(state, f)
        return path

    def test_no_hardcoded_com_agy_path_in_source(self):
        src_path = os.path.join(OVERLAY_LIB, "daemon", "executor.py")
        with open(src_path, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertNotIn("/data/user/0/com.agy/files", content)

    def test_dynamic_prefix_home_and_app_id(self):
        self.assertEqual(executor.application_id_from_prefix(self.prefix_dir), "com.agy.dev")
        env = executor._build_task_env(convo_id="conv-1", project=self.tmp_dir)
        self.assertEqual(env["HOME"], self.home_dir)
        self.assertEqual(env["PREFIX"], self.prefix_dir)
        self.assertTrue(env["PATH"].startswith(os.path.join(self.prefix_dir, "bin")))
        self.assertEqual(env["AGY_CONVERSATION_ID"], "conv-1")
        self.assertEqual(env["AGY_PROJECT_DIR"], self.tmp_dir)
        self.assertEqual(executor._resolve_task_cwd(None), self.home_dir)

    def test_connectrpc_ai_prompt_start_and_send(self):
        self._write_core_state(ready=True, csrf="csrf-xyz-123")
        project_dir = os.path.join(self.tmp_dir, "my_project")
        os.makedirs(project_dir, exist_ok=True)
        task = {
            "id": "task_ai_1",
            "name": "Nightly Review",
            "command": "",
            "prompt": "Check 'single' and \"double\" quotes + `backticks`",
            "project": project_dir,
            "conversation_id": "",
        }
        executor.save_tasks([task])

        with mock.patch.object(executor, "notify", return_value=True) as mock_notify:
            executor._run_task_worker(task)

        reqs = _MockConnectRpcHandler.recorded_requests
        self.assertEqual(len(reqs), 2)

        # 1. StartCascade
        self.assertEqual(reqs[0]["path"], "/exa.language_server_pb.LanguageServerService/StartCascade")
        self.assertEqual(reqs[0]["csrf"], "csrf-xyz-123")
        self.assertEqual(reqs[0]["connect_version"], "1")
        self.assertEqual(reqs[0]["body"].get("source"), 1)
        uris = reqs[0]["body"].get("workspaceUris", [])
        self.assertEqual(len(uris), 1)
        self.assertTrue(uris[0].startswith("file://"))

        # 2. SendUserCascadeMessage
        self.assertEqual(reqs[1]["path"], "/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage")
        self.assertEqual(reqs[1]["csrf"], "csrf-xyz-123")
        self.assertEqual(reqs[1]["body"].get("cascadeId"), "cascade-test-999")
        self.assertEqual(
            reqs[1]["body"].get("items"),
            [{"text": "Check 'single' and \"double\" quotes + `backticks`"}],
        )

        # Verify persisted task state
        saved = executor.load_tasks()
        self.assertEqual(saved[0]["last_run_status"], "success")
        self.assertEqual(saved[0]["last_cascade_id"], "cascade-test-999")
        self.assertGreaterEqual(mock_notify.call_count, 2)

    def test_connectrpc_reuses_existing_conversation_id(self):
        self._write_core_state(ready=True, csrf="csrf-existing")
        task = {
            "id": "task_ai_2",
            "name": "Follow-up",
            "command": "",
            "prompt": "Continue analysis",
            "conversation_id": "existing-cascade-42",
        }
        executor.save_tasks([task])

        with mock.patch.object(executor, "notify", return_value=True):
            executor._run_task_worker(task)

        reqs = _MockConnectRpcHandler.recorded_requests
        self.assertEqual(len(reqs), 1)
        self.assertEqual(reqs[0]["path"], "/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage")
        self.assertEqual(reqs[0]["body"]["cascadeId"], "existing-cascade-42")
        self.assertEqual(reqs[0]["body"]["items"], [{"text": "Continue analysis"}])

    def test_prompt_quote_injection_safe_fallback_when_core_missing(self):
        # No core.state.json written -> ConnectRPC must fail and fall back to safe argv notify()
        pwn_marker = os.path.join(self.tmp_dir, "INJECTED")
        dangerous_prompt = f"hi'; touch '{pwn_marker}'; echo \"$(touch '{pwn_marker}')\" `touch '{pwn_marker}'`"
        task = {
            "id": "task_inj_1",
            "name": "Quote Safety Test",
            "command": "",
            "prompt": dangerous_prompt,
        }
        executor.save_tasks([task])

        subprocess_calls = []

        def fake_run(cmd, *args, **kwargs):
            subprocess_calls.append((cmd, kwargs))
            return mock.Mock(returncode=0, stdout="", stderr="")

        with mock.patch.object(executor.subprocess, "run", side_effect=fake_run):
            executor._run_task_worker(task)

        # Must never create pwn_marker and must never invoke shell=True for prompt tasks
        self.assertFalse(os.path.exists(pwn_marker))
        self.assertGreaterEqual(len(subprocess_calls), 3)
        for cmd, kwargs in subprocess_calls:
            self.assertIsInstance(cmd, list, "Fallback notification must use safe argv list, not shell string")
            self.assertFalse(kwargs.get("shell", False), "Prompt fallback must never use shell=True")

        # Verify fallback notification included the raw prompt safely in argv
        fallback_calls = [c for c, _ in subprocess_calls if "AI Task Trigger" in c]
        self.assertEqual(len(fallback_calls), 1)
        self.assertIn(f"Quote Safety Test: {dangerous_prompt[:140]}", fallback_calls[0])

        saved = executor.load_tasks()
        self.assertEqual(saved[0]["last_run_status"], "failed")
        self.assertIn("fallback notification", saved[0]["last_output"].lower())

    def test_shell_command_uses_dynamic_home_and_prefix(self):
        out_file = os.path.join(self.tmp_dir, "env_dump.json")
        py_cmd = (
            f'"{sys.executable}" -c '
            f'"import json, os; '
            f'open({out_file!r}, \'w\').write(json.dumps({{\'HOME\': os.environ.get(\'HOME\'), \'PREFIX\': os.environ.get(\'PREFIX\'), \'CWD\': os.getcwd()}}))"'
        )
        task = {
            "id": "task_cmd_1",
            "name": "Env Check",
            "command": py_cmd,
            "prompt": "",
        }
        executor.save_tasks([task])

        with mock.patch.object(executor, "notify", return_value=True):
            executor._run_task_worker(task)

        self.assertTrue(os.path.isfile(out_file))
        with open(out_file, "r", encoding="utf-8") as f:
            dumped = json.load(f)
        self.assertEqual(os.path.normcase(dumped["HOME"]), os.path.normcase(self.home_dir))
        self.assertEqual(os.path.normcase(dumped["PREFIX"]), os.path.normcase(self.prefix_dir))
        self.assertEqual(os.path.normcase(dumped["CWD"]), os.path.normcase(self.home_dir))

    def test_agy_notify_cli_posts_gateway_envelope_with_dynamic_port(self):
        with open(os.path.join(self.home_dir, "logs", "gateway.port"), "w", encoding="utf-8") as f:
            f.write(f"{self.port}\n")
        with open(os.path.join(self.home_dir, "logs", "gateway.token"), "w", encoding="utf-8") as f:
            f.write("gw-tok-secret-123\n")

        notify_bin = os.path.join(ENV_DIR, "overlay", "bin", "agy-notify")
        clean_env = dict(os.environ)
        clean_env["HOME"] = self.home_dir
        clean_env["PREFIX"] = self.prefix_dir
        clean_env.pop("AGY_GATEWAY_PORT", None)
        clean_env.pop("AGY_GATEWAY_TOKEN", None)
        clean_env.pop("AGY_GATEWAY_TOKEN_FILE", None)

        res = subprocess.run(
            ["bash", notify_bin, "post", "-t", "Agent Done", "-m", "All checks passed", "--tag", "turn_abc", "--channel", "agy_alerts"],
            env=clean_env,
            capture_output=True,
            text=True,
            timeout=15,
        )
        self.assertEqual(res.returncode, 0, f"agy-notify failed: {res.stderr} {res.stdout}")
        reqs = _MockConnectRpcHandler.recorded_requests
        self.assertEqual(len(reqs), 1)
        self.assertEqual(reqs[0]["path"], "/gateway")
        self.assertEqual(reqs[0]["gateway_token"], "gw-tok-secret-123")
        self.assertEqual(reqs[0]["body"]["command"], "notify")
        payload = reqs[0]["body"]["payload"]
        self.assertEqual(payload["action"], "post")
        self.assertEqual(payload["tag"], "turn_abc")
        self.assertEqual(payload["title"], "Agent Done")
        self.assertEqual(payload["message"], "All checks passed")
        self.assertEqual(payload["channel"], "agy_alerts")

    def test_executor_notify_direct_gateway_and_fallback(self):
        port_path = os.path.join(self.home_dir, "logs", "gateway.port")
        token_path = os.path.join(self.home_dir, "logs", "gateway.token")
        with open(port_path, "w", encoding="utf-8") as f:
            f.write(f"{self.port}\n")
        with open(token_path, "w", encoding="utf-8") as f:
            f.write("gw-exec-token-xyz\n")

        clean_env = {k: v for k, v in os.environ.items()
                     if k not in ("AGY_GATEWAY_PORT", "AGY_GATEWAY_TOKEN", "AGY_GATEWAY_TOKEN_FILE")}
        with mock.patch.dict(os.environ, clean_env, clear=True):
            with mock.patch.object(executor.subprocess, "run") as mock_run:
                ok = executor.notify("Task Complete", "Summary here", tag="agy_turn_1", channel="agy_alerts", cascade_id="conv-direct-42")
        self.assertTrue(ok)
        mock_run.assert_not_called()
        reqs = _MockConnectRpcHandler.recorded_requests
        self.assertEqual(len(reqs), 1)
        self.assertEqual(reqs[0]["path"], "/gateway")
        self.assertEqual(reqs[0]["gateway_token"], "gw-exec-token-xyz")
        self.assertEqual(reqs[0]["body"]["payload"]["channel"], "agy_alerts")
        self.assertEqual(reqs[0]["body"]["payload"]["cascade_id"], "conv-direct-42")

        # Removing gateway.token cleanly falls back to agy-notify subprocess with --channel and --cascade-id
        os.remove(token_path)
        fallback_calls = []
        with mock.patch.dict(os.environ, clean_env, clear=True):
            with mock.patch.object(executor.subprocess, "run", side_effect=lambda cmd, **kw: fallback_calls.append(cmd) or mock.Mock(returncode=0)):
                ok2 = executor.notify("Task Complete", "Fallback body", tag="agy_turn_2", channel="agy_alerts", cascade_id="conv-fallback-99")
        self.assertTrue(ok2)
        self.assertEqual(len(fallback_calls), 1)
        self.assertIn("--channel", fallback_calls[0])
        self.assertIn("agy_alerts", fallback_calls[0])
        self.assertIn("--cascade-id", fallback_calls[0])
        self.assertIn("conv-fallback-99", fallback_calls[0])

    def test_scheduler_turn_notifications_ignore_subagents(self):
        user_id = "11111111-2222-3333-4444-555555555555"
        sub_id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        user_log = os.path.join(self.home_dir, ".gemini", "antigravity-app", "brain", user_id, ".system_generated", "logs", "transcript.jsonl")
        sub_log = os.path.join(self.home_dir, ".gemini", "antigravity-app", "brain", sub_id, ".system_generated", "logs", "transcript.jsonl")
        os.makedirs(os.path.dirname(user_log), exist_ok=True)
        os.makedirs(os.path.dirname(sub_log), exist_ok=True)

        def write_lines(path, records):
            with open(path, "w", encoding="utf-8") as f:
                for rec in records:
                    f.write(json.dumps(rec) + "\n")

        write_lines(user_log, [
            {"step_index": 0, "type": "USER_INPUT", "source": "USER_EXPLICIT", "status": "DONE", "content": "hi"},
        ])
        write_lines(sub_log, [
            {"step_index": 0, "type": "SYSTEM_MESSAGE", "source": "SYSTEM", "status": "DONE", "content": "init"},
        ])

        brain_glob = os.path.join(self.home_dir, ".gemini", "antigravity-app", "brain", "*", ".system_generated", "logs", "transcript.jsonl")
        state = {}
        posted = []

        with mock.patch.object(scheduler, "_post_gateway_notification", side_effect=lambda *a, **kw: posted.append((a, kw))):
            # Baseline scan: marks user_log as working, ignores sub_log
            scheduler._check_conversation_turns(state, brain_glob=brain_glob)
            self.assertEqual(len(posted), 0)

            # Both write a final PLANNER_RESPONSE DONE with empty tool_calls (ensure size changes)
            write_lines(user_log, [
                {"step_index": 0, "type": "USER_INPUT", "source": "USER_EXPLICIT", "status": "DONE", "content": "hi"},
                {"step_index": 1, "type": "PLANNER_RESPONSE", "source": "MODEL", "status": "DONE", "content": "All **done**!"},
            ])
            write_lines(sub_log, [
                {"step_index": 0, "type": "SYSTEM_MESSAGE", "source": "SYSTEM", "status": "DONE", "content": "init"},
                {"step_index": 1, "type": "PLANNER_RESPONSE", "source": "MODEL", "status": "DONE", "content": "Subagent summary"},
            ])
            scheduler._check_conversation_turns(state, brain_glob=brain_glob)

        self.assertEqual(len(posted), 1, "Only the user conversation turn should notify")
        args, kwargs = posted[0]
        self.assertEqual(args[0], "Antigravity: Task Complete")
        self.assertEqual(args[1], "All done!")
        self.assertEqual(kwargs.get("tag"), f"agy_turn_{user_id[:12]}")
        self.assertEqual(kwargs.get("channel"), "agy_alerts")
        self.assertEqual(kwargs.get("cascade_id"), user_id)

        # Verify ask_question turn also forwards cascade_id
        posted.clear()
        with mock.patch.object(scheduler, "_post_gateway_notification", side_effect=lambda *a, **kw: posted.append((a, kw))):
            write_lines(user_log, [
                {"step_index": 2, "type": "PLANNER_RESPONSE", "source": "MODEL", "status": "DONE",
                 "tool_calls": [{"name": "ask_question"}]},
            ])
            scheduler._check_conversation_turns(state, brain_glob=brain_glob)
        self.assertEqual(len(posted), 1)
        self.assertEqual(posted[0][0][0], "Antigravity: Input Needed")
        self.assertEqual(posted[0][1].get("cascade_id"), user_id)

    def test_daemon_lock_export_and_flock(self):
        import daemon
        self.assertTrue(hasattr(daemon, "LOCK_FILE"))
        self.assertEqual(daemon.LOCK_FILE, os.path.join(daemon.CONFIG_DIR, "daemon.lock"))
        self.assertEqual(executor.LOCK_FILE, os.path.join(daemon.CONFIG_DIR, "daemon.lock"))
        self.assertTrue(daemon.LOCK_FILE.endswith("daemon.lock"))

        daemon_bin = os.path.join(ENV_DIR, "overlay", "bin", "agy-daemon")
        with open(daemon_bin, "r", encoding="utf-8") as f:
            src = f.read()
        self.assertIn("fcntl.flock", src)
        self.assertIn("LOCK_FILE", src)
        self.assertIn("os._exit(0)", src)
        self.assertNotIn("/data/user/0/com.agy/files", src)


if __name__ == "__main__":
    unittest.main()
