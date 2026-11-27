#!/usr/bin/env python3
"""
Golden tests for agy-fix-paths (reusable across bash and Rust implementations via AGY_FIX_PATHS_BIN).
Covers:
- Stale Android prefixes (/data/data/... and /data/user/<id>/.../files/usr)
- Already-matching $PREFIX files left completely untouched (inode preserved)
- Line-1 #!/usr/bin/env and #!/usr/bin/env -S shebangs vs line-2 #!/usr/bin/env ignored
- Combined stale prefix + line-1 #!/usr/bin/env file
- Binary files with NUL bytes left untouched (inode preserved)
- Symlinks inside tree and passed directly on CLI left untouched
- File permissions (0755, 0600, 0640, 0444) and owner preserved
- Paths and $PREFIX containing special characters (&, \\, @, spaces)
- Idempotency on second run and zero leftover temp files
"""

import os
import sys
sys.dont_write_bytecode = True
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_DIR = os.path.dirname(TESTS_DIR)
DEFAULT_FIX_PATHS = os.path.join(ENV_DIR, "overlay", "bin", "agy-fix-paths")


def _run_fix_paths(prefix, targets=None, extra_env=None):
    bin_path = os.environ.get("AGY_FIX_PATHS_BIN", DEFAULT_FIX_PATHS)
    with open(bin_path, "rb") as f:
        head = f.read(2)
    cmd = ["bash", bin_path] if head == b"#!" and bin_path == DEFAULT_FIX_PATHS else [bin_path]
    # Note: shell wrappers or direct ELF executables can also be invoked directly when +x;
    # for non-ELF scripts in repo without +x in git checkouts, bash is safest when #! starts with bash.
    with open(bin_path, "rb") as f:
        first_line = f.readline()
    if b"bash" in first_line or b"/bin/sh" in first_line:
        cmd = ["bash", bin_path]
    else:
        cmd = [bin_path]
    if targets:
        cmd.extend(targets)

    env = dict(os.environ)
    env["PREFIX"] = prefix
    if extra_env:
        env.update(extra_env)
    return subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=30)


def _parse_rewrote_count(stdout):
    m = re.search(r"agy-fix-paths:\s+rewrote\s+(\d+)\s+file\(s\)", stdout)
    return int(m.group(1)) if m else None


class TestFixPathsGolden(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp(prefix="agy_fix_paths_test_")
        self.prefix = os.path.join(self.tmp_dir, "user0", "com.agy.new", "files", "usr")
        os.makedirs(self.prefix, exist_ok=True)

    def tearDown(self):
        for root, dirs, files in os.walk(self.tmp_dir):
            for d in dirs:
                try:
                    os.chmod(os.path.join(root, d), 0o700)
                except OSError:
                    pass
            for fn in files:
                fp = os.path.join(root, fn)
                if not os.path.islink(fp):
                    try:
                        os.chmod(fp, 0o600)
                    except OSError:
                        pass
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def test_golden_tree_rewrites_and_preservations(self):
        tree = os.path.join(self.prefix, "tree")
        ext_dir = os.path.join(self.tmp_dir, "external")
        os.makedirs(tree, exist_ok=True)
        os.makedirs(ext_dir, exist_ok=True)

        # 1. Stale prefixes across different app IDs and user IDs
        f_termux = os.path.join(tree, "termux_script.sh")
        with open(f_termux, "w", encoding="utf-8") as f:
            f.write("#!/data/data/com.termux/files/usr/bin/bash\nLIB=/data/data/com.termux/files/usr/lib\n")
        os.chmod(f_termux, 0o755)

        f_other = os.path.join(tree, "other_user.conf")
        with open(f_other, "w", encoding="utf-8") as f:
            f.write("prefix=/data/user/10/com.other.app/files/usr\nbin=/data/user/0/com.agy.old/files/usr/bin\n")
        os.chmod(f_other, 0o640)

        # 2. Already matching $PREFIX (must not be rewritten or replaced)
        f_clean = os.path.join(tree, "already_clean.txt")
        with open(f_clean, "w", encoding="utf-8") as f:
            f.write(f"#!{self.prefix}/bin/python3\npath={self.prefix}/share\n")
        os.chmod(f_clean, 0o600)
        clean_ino = os.stat(f_clean).st_ino

        # 3. Line-1 #!/usr/bin/env and #!/usr/bin/env -S shebangs
        f_env1 = os.path.join(tree, "env_py.py")
        with open(f_env1, "w", encoding="utf-8") as f:
            f.write("#!/usr/bin/env python3\nprint('hi')\n")
        os.chmod(f_env1, 0o755)

        f_env_s = os.path.join(tree, "env_s.py")
        with open(f_env_s, "w", encoding="utf-8") as f:
            f.write("#! /usr/bin/env -S python3 -u\nprint('s')\n")
        os.chmod(f_env_s, 0o755)

        # 4. #!/usr/bin/env ONLY on line 2 (must NOT be touched)
        f_env_line2 = os.path.join(tree, "env_line2.txt")
        with open(f_env_line2, "w", encoding="utf-8") as f:
            f.write("# comment line 1\n#!/usr/bin/env bash\necho ok\n")
        os.chmod(f_env_line2, 0o644)
        line2_ino = os.stat(f_env_line2).st_ino

        # 5. Binary file containing NUL bytes and stale prefix string (must NOT be touched)
        f_bin = os.path.join(tree, "native.bin")
        bin_bytes = b"\x7fELF\x00\x01\x02/data/data/com.termux/files/usr/bin/sh\x00end"
        with open(f_bin, "wb") as f:
            f.write(bin_bytes)
        os.chmod(f_bin, 0o755)
        bin_ino = os.stat(f_bin).st_ino

        # 6. Symlink inside tree pointing to external file with stale prefix
        ext_target = os.path.join(ext_dir, "outside.sh")
        with open(ext_target, "w", encoding="utf-8") as f:
            f.write("#!/data/data/com.termux/files/usr/bin/sh\n")
        link_inside = os.path.join(tree, "link_to_outside.sh")
        os.symlink(ext_target, link_inside)

        # 7. Special permission modes (0600, 0444)
        f_secret = os.path.join(tree, "secret.cfg")
        with open(f_secret, "w", encoding="utf-8") as f:
            f.write("dir=/data/user/0/com.agy.dev/files/usr/etc\n")
        os.chmod(f_secret, 0o600)
        secret_st = os.stat(f_secret)

        f_readonly = os.path.join(tree, "readonly.cfg")
        with open(f_readonly, "w", encoding="utf-8") as f:
            f.write("dir=/data/data/com.termux/files/usr/etc\n")
        os.chmod(f_readonly, 0o444)

        # 8. Paths with spaces and special characters (&, \, @)
        special_dir = os.path.join(tree, "dir with spaces", "sub & @ dir")
        os.makedirs(special_dir, exist_ok=True)
        f_special = os.path.join(special_dir, "file & @ \\.sh")
        with open(f_special, "w", encoding="utf-8") as f:
            f.write("#!/data/data/com.termux/files/usr/bin/sh\necho ok\n")
        os.chmod(f_special, 0o750)

        res = _run_fix_paths(self.prefix, targets=[tree])
        self.assertEqual(res.returncode, 0, f"stderr: {res.stderr}\nstdout: {res.stdout}")
        self.assertEqual(_parse_rewrote_count(res.stdout), 7, res.stdout)

        # Verify rewritten contents
        with open(f_termux, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), f"#!{self.prefix}/bin/bash\nLIB={self.prefix}/lib\n")
        self.assertEqual(stat.S_IMODE(os.stat(f_termux).st_mode), 0o755)

        with open(f_other, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), f"prefix={self.prefix}\nbin={self.prefix}/bin\n")
        self.assertEqual(stat.S_IMODE(os.stat(f_other).st_mode), 0o640)

        with open(f_env1, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), f"#!{self.prefix}/bin/python3\nprint('hi')\n")
        self.assertEqual(stat.S_IMODE(os.stat(f_env1).st_mode), 0o755)

        with open(f_env_s, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), f"#!{self.prefix}/bin/python3 -u\nprint('s')\n")

        with open(f_secret, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), f"dir={self.prefix}/etc\n")
        new_secret_st = os.stat(f_secret)
        self.assertEqual(stat.S_IMODE(new_secret_st.st_mode), 0o600)
        self.assertEqual((new_secret_st.st_uid, new_secret_st.st_gid), (secret_st.st_uid, secret_st.st_gid))

        with open(f_readonly, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), f"dir={self.prefix}/etc\n")
        self.assertEqual(stat.S_IMODE(os.stat(f_readonly).st_mode), 0o444)

        with open(f_special, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), f"#!{self.prefix}/bin/sh\necho ok\n")
        self.assertEqual(stat.S_IMODE(os.stat(f_special).st_mode), 0o750)

        # Verify untouched files kept content and inode
        self.assertEqual(os.stat(f_clean).st_ino, clean_ino)
        self.assertEqual(os.stat(f_env_line2).st_ino, line2_ino)
        with open(f_env_line2, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), "# comment line 1\n#!/usr/bin/env bash\necho ok\n")
        self.assertEqual(os.stat(f_bin).st_ino, bin_ino)
        with open(f_bin, "rb") as f:
            self.assertEqual(f.read(), bin_bytes)

        # Verify symlink stayed symlink and target was untouched
        self.assertTrue(os.path.islink(link_inside))
        self.assertEqual(os.readlink(link_inside), ext_target)
        with open(ext_target, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), "#!/data/data/com.termux/files/usr/bin/sh\n")

        # Verify no leftover temp files
        leftover = [fn for r, _, fns in os.walk(tree) for fn in fns if ".agyfix." in fn]
        self.assertEqual(leftover, [])

        # Verify idempotency on second run
        res2 = _run_fix_paths(self.prefix, targets=[tree])
        self.assertEqual(res2.returncode, 0, res2.stderr)
        self.assertEqual(_parse_rewrote_count(res2.stdout), 0, res2.stdout)

    def test_combined_env_shebang_and_stale_prefix_in_same_file(self):
        fp = os.path.join(self.prefix, "combo.py")
        with open(fp, "w", encoding="utf-8") as f:
            f.write("#!/usr/bin/env python3\nDATA='/data/data/com.termux/files/usr/share'\n")
        os.chmod(fp, 0o755)

        res = _run_fix_paths(self.prefix, targets=[fp])
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertGreaterEqual(_parse_rewrote_count(res.stdout), 1, res.stdout)
        with open(fp, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), f"#!{self.prefix}/bin/python3\nDATA='{self.prefix}/share'\n")

        res2 = _run_fix_paths(self.prefix, targets=[fp])
        self.assertEqual(res2.returncode, 0, res2.stderr)
        self.assertEqual(_parse_rewrote_count(res2.stdout), 0, res2.stdout)

    def test_symlink_passed_directly_as_target_is_skipped(self):
        real_f = os.path.join(self.tmp_dir, "real.sh")
        with open(real_f, "w", encoding="utf-8") as f:
            f.write("#!/data/data/com.termux/files/usr/bin/sh\n")
        cli_link = os.path.join(self.tmp_dir, "cli_link.sh")
        os.symlink(real_f, cli_link)

        res = _run_fix_paths(self.prefix, targets=[cli_link])
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(_parse_rewrote_count(res.stdout), 0, res.stdout)
        self.assertTrue(os.path.islink(cli_link))
        with open(real_f, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), "#!/data/data/com.termux/files/usr/bin/sh\n")

    def test_special_characters_in_prefix_itself(self):
        special_prefix = os.path.join(self.tmp_dir, "dir with space", "a&b@c\\d", "files", "usr")
        os.makedirs(special_prefix, exist_ok=True)
        fp = os.path.join(special_prefix, "run.py")
        with open(fp, "w", encoding="utf-8") as f:
            f.write("#!/usr/bin/env python3\nHOME_OLD='/data/data/com.termux/files/usr'\n")

        res = _run_fix_paths(special_prefix)
        self.assertEqual(res.returncode, 0, f"stderr: {res.stderr}\nstdout: {res.stdout}")
        with open(fp, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), f"#!{special_prefix}/bin/python3\nHOME_OLD='{special_prefix}'\n")

        res2 = _run_fix_paths(special_prefix)
        self.assertEqual(res2.returncode, 0, res2.stderr)
        self.assertEqual(_parse_rewrote_count(res2.stdout), 0, res2.stdout)


if __name__ == "__main__":
    unittest.main()
