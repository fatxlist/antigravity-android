#!/usr/bin/env python3
"""
test_builder.py - Unit tests for modules/builder components.
"""
import unittest
import tempfile
import zipfile
import shutil
import sys
import os

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
BUILDER_DIR = os.path.dirname(TESTS_DIR)
sys.path.insert(0, BUILDER_DIR)

import align


class TestBuilder(unittest.TestCase):

    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp(prefix="test_builder_")

    def tearDown(self):
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def test_python_zipalign(self):
        raw_zip = os.path.join(self.tmp_dir, "raw.zip")
        aligned_zip = os.path.join(self.tmp_dir, "aligned.zip")

        # Create an unaligned zip with uncompressed entries of odd lengths
        with zipfile.ZipFile(raw_zip, "w") as z:
            z.writestr("test1.txt", "123", compress_type=zipfile.ZIP_STORED)
            z.writestr("test2.bin", "1234567", compress_type=zipfile.ZIP_STORED)
            z.writestr("compressed.dat", "Hello world compressed content", compress_type=zipfile.ZIP_DEFLATED)

        # Run pure Python 4-byte aligner
        ok = align.align_with_python(raw_zip, aligned_zip, alignment=4)
        self.assertTrue(ok)
        self.assertTrue(os.path.isfile(aligned_zip))

        # Inspect offsets in aligned zip
        with open(aligned_zip, "rb") as f:
            data = f.read()

        with zipfile.ZipFile(aligned_zip, "r") as z:
            self.assertIsNone(z.testzip(), "CRC-32 verification failed on aligned ZIP!")
            self.assertEqual(z.read("test1.txt"), b"123")
            self.assertEqual(z.read("test2.bin"), b"1234567")
            self.assertEqual(z.read("compressed.dat"), b"Hello world compressed content")
            for info in z.infolist():
                if info.compress_type == 0:
                    # In a local header: fixed 30 bytes + len(name) + len(extra)
                    # We verify data start offset is a multiple of 4
                    header_offset = info.header_offset
                    data_offset = header_offset + 30 + len(info.filename.encode("utf-8")) + len(info.extra)
                    self.assertEqual(data_offset % 4, 0, f"Entry {info.filename} at offset {data_offset} not 4-byte aligned!")

    def test_01_gen_config_build_dir(self):
        import subprocess
        project_root = os.path.abspath(os.path.join(BUILDER_DIR, "..", ".."))
        build_dir = os.path.join(self.tmp_dir, "build")
        cmd = [sys.executable, os.path.join(BUILDER_DIR, "01_gen_config.py"), build_dir]
        res = subprocess.run(cmd, capture_output=True, text=True, cwd=project_root)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertTrue(os.path.isfile(os.path.join(build_dir, "res", "values", "gen.xml")))
        self.assertTrue(os.path.isfile(os.path.join(build_dir, "gen", "com", "agy", "BuildConfig.java")))
        self.assertTrue(os.path.isfile(os.path.join(build_dir, "AndroidManifest.xml")))

    def test_02_r_java_overlay_and_dedup(self):
        import subprocess
        project_root = os.path.abspath(os.path.join(BUILDER_DIR, "..", ".."))
        tools_dir = os.path.join(project_root, "tools")
        android_jar = os.path.join(tools_dir, "android.jar")
        res_dir = os.path.join(project_root, "app", "src", "main", "res")

        # Test release mode (com.agy) -> single call
        build_dir = os.path.join(self.tmp_dir, "build_rel")
        cmd_cfg = [sys.executable, os.path.join(BUILDER_DIR, "01_gen_config.py"), build_dir]
        subprocess.run(cmd_cfg, check=True, cwd=project_root)
        gen_dir = os.path.join(build_dir, "gen")
        manifest = os.path.join(build_dir, "AndroidManifest.xml")
        overlay_res = os.path.join(build_dir, "res")

        cmd_r = ["bash", os.path.join(BUILDER_DIR, "02_r_java.sh"), gen_dir, manifest, res_dir, android_jar, overlay_res]
        res = subprocess.run(cmd_r, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertNotIn("Generating fallback com.agy.R", res.stdout)
        self.assertTrue(os.path.isfile(os.path.join(gen_dir, "com", "agy", "R.java")))

        # Test dev mode (com.agy.dev) -> generates both com.agy.dev.R and fallback com.agy.R
        build_dir_dev = os.path.join(self.tmp_dir, "build_dev")
        subprocess.run(cmd_cfg[:2] + [build_dir_dev], check=True, cwd=project_root, env={**os.environ, "DEV_MODE": "1"})
        gen_dir_dev = os.path.join(build_dir_dev, "gen")
        manifest_dev = os.path.join(build_dir_dev, "AndroidManifest.xml")
        overlay_res_dev = os.path.join(build_dir_dev, "res")

        cmd_r_dev = ["bash", os.path.join(BUILDER_DIR, "02_r_java.sh"), gen_dir_dev, manifest_dev, res_dir, android_jar, overlay_res_dev]
        res_dev = subprocess.run(cmd_r_dev, capture_output=True, text=True)
        self.assertEqual(res_dev.returncode, 0, res_dev.stderr)
        self.assertIn("Generating fallback com.agy.R", res_dev.stdout)
        self.assertTrue(os.path.isfile(os.path.join(gen_dir_dev, "com", "agy", "R.java")))
        self.assertTrue(os.path.isfile(os.path.join(gen_dir_dev, "com", "agy", "dev", "R.java")))

    def test_03_and_04_compile_and_dex_argfile(self):
        import subprocess
        project_root = os.path.abspath(os.path.join(BUILDER_DIR, "..", ".."))
        tools_dir = os.path.join(project_root, "tools")
        android_jar = os.path.join(tools_dir, "android.jar")
        r8_jar = os.path.join(tools_dir, "r8.jar")

        build_dir = os.path.join(self.tmp_dir, "build_pipeline")
        subprocess.run([sys.executable, os.path.join(BUILDER_DIR, "01_gen_config.py"), build_dir], check=True, cwd=project_root)
        gen_dir = os.path.join(build_dir, "gen")
        manifest = os.path.join(build_dir, "AndroidManifest.xml")
        res_dir = os.path.join(project_root, "app", "src", "main", "res")
        overlay_res = os.path.join(build_dir, "res")
        subprocess.run(["bash", os.path.join(BUILDER_DIR, "02_r_java.sh"), gen_dir, manifest, res_dir, android_jar, overlay_res], check=True)

        obj_dir = os.path.join(build_dir, "obj")
        src_dir = os.path.join(project_root, "app", "src", "main", "java")
        cmd_comp = ["bash", os.path.join(BUILDER_DIR, "03_compile_java.sh"), obj_dir, gen_dir, src_dir, android_jar]
        res_comp = subprocess.run(cmd_comp, capture_output=True, text=True, env={**os.environ, "BUILD_DIR": build_dir})
        self.assertEqual(res_comp.returncode, 0, res_comp.stderr)
        self.assertTrue(os.path.isfile(os.path.join(build_dir, "java_sources.txt")))

        dex_dir = os.path.join(build_dir, "dex")
        cmd_dex = ["bash", os.path.join(BUILDER_DIR, "04_dex.sh"), dex_dir, obj_dir, r8_jar, android_jar, "24"]
        res_dex = subprocess.run(cmd_dex, capture_output=True, text=True, env={**os.environ, "BUILD_DIR": build_dir})
        self.assertEqual(res_dex.returncode, 0, res_dex.stderr)
        self.assertTrue(os.path.isfile(os.path.join(build_dir, "class_files.txt")))
        self.assertTrue(os.path.isfile(os.path.join(dex_dir, "classes.dex")))

    def test_05_sign_fail_closed(self):
        import subprocess
        env = {k: v for k, v in os.environ.items()
               if not k.startswith("AGY_RELEASE_") and k not in ("DEV_MODE", "ALLOW_DEBUG_SIGN", "AGY_SIGN_KEY_PASS", "AGY_SIGN_STORE_PASS")}
        dummy_aligned = os.path.join(self.tmp_dir, "aligned.apk")
        dummy_out = os.path.join(self.tmp_dir, "out.apk")
        with open(dummy_aligned, "wb") as f:
            f.write(b"PK")
        res = subprocess.run(
            ["bash", os.path.join(BUILDER_DIR, "08_sign.sh"), dummy_aligned, dummy_out],
            capture_output=True, text=True, env=env
        )
        self.assertNotEqual(res.returncode, 0, "08_sign.sh must fail closed without release signing credentials")
        self.assertIn("Refusing to sign release APK", res.stderr)

    def test_single_pass_package_aligned_apk_and_check(self):
        import struct
        base_apk = os.path.join(self.tmp_dir, "base.apk")
        with zipfile.ZipFile(base_apk, "w") as z:
            z.writestr("AndroidManifest.xml", b"MANIFEST_DATA_123", compress_type=zipfile.ZIP_STORED)
            z.writestr("lib/arm64-v8a/libbase.so", b"\x7fELF" + b"\x01" * 64, compress_type=zipfile.ZIP_STORED)

        dex_path = os.path.join(self.tmp_dir, "classes.dex")
        with open(dex_path, "wb") as f:
            f.write(b"dex\n035\x00" + b"A" * 128)

        assets_dir = os.path.join(self.tmp_dir, "staging", "assets")
        os.makedirs(os.path.join(assets_dir, "runtime"), exist_ok=True)
        os.makedirs(os.path.join(assets_dir, "web"), exist_ok=True)
        with open(os.path.join(assets_dir, "runtime", "libtest.so"), "wb") as f:
            f.write(b"\x7fELF" + b"\x00" * 256)
        with open(os.path.join(assets_dir, "web", "index.html"), "w", encoding="utf-8") as f:
            f.write("<html><body>ok</body></html>")
        with open(os.path.join(assets_dir, "data.zip"), "wb") as f:
            f.write(b"PK\x05\x06" + b"\x00" * 18)

        for fast_store in (False, True):
            out_apk = os.path.join(self.tmp_dir, f"out_{int(fast_store)}.apk")
            ok = align.package_aligned_apk(base_apk, out_apk, dex_path, assets_dir, alignment=4, fast_store=fast_store)
            self.assertTrue(ok)
            chk_ok, errs = align.check_alignment(out_apk, alignment=4)
            self.assertTrue(chk_ok, f"alignment errors (fast={fast_store}): {errs}")
            with zipfile.ZipFile(out_apk, "r") as zout, open(out_apk, "rb") as fout:
                names = set(zout.namelist())
                self.assertEqual(names, {
                    "AndroidManifest.xml",
                    "lib/arm64-v8a/libbase.so",
                    "classes.dex",
                    "assets/runtime/libtest.so",
                    "assets/web/index.html",
                    "assets/data.zip",
                })
                self.assertEqual(zout.read("classes.dex"), b"dex\n035\x00" + b"A" * 128)
                self.assertEqual(zout.read("assets/web/index.html"), b"<html><body>ok</body></html>")
                base_so = zout.getinfo("lib/arm64-v8a/libbase.so")
                self.assertEqual(base_so.compress_type, zipfile.ZIP_STORED)
                fout.seek(base_so.header_offset + 26)
                nlen, elen = struct.unpack("<HH", fout.read(4))
                self.assertEqual((base_so.header_offset + 30 + nlen + elen) % 4096, 0)

                asset_so = zout.getinfo("assets/runtime/libtest.so")
                if fast_store:
                    self.assertEqual(zout.getinfo("classes.dex").compress_type, zipfile.ZIP_STORED)
                    self.assertEqual(zout.getinfo("assets/web/index.html").compress_type, zipfile.ZIP_STORED)
                    self.assertEqual(asset_so.compress_type, zipfile.ZIP_STORED)
                    fout.seek(asset_so.header_offset + 26)
                    anlen, aelen = struct.unpack("<HH", fout.read(4))
                    self.assertEqual((asset_so.header_offset + 30 + anlen + aelen) % 4096, 0)
                else:
                    self.assertEqual(zout.getinfo("classes.dex").compress_type, zipfile.ZIP_DEFLATED)
                    self.assertEqual(zout.getinfo("assets/web/index.html").compress_type, zipfile.ZIP_DEFLATED)
                    self.assertEqual(asset_so.compress_type, zipfile.ZIP_DEFLATED)

    def test_fast_stage_assets_manifest_reuse(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("stage_assets_mod", os.path.join(BUILDER_DIR, "05_stage_assets.py"))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)

        env_src = os.path.join(self.tmp_dir, "env_src")
        env_dst = os.path.join(self.tmp_dir, "env_dst")
        os.makedirs(os.path.join(env_dst, "bootstrap"), exist_ok=True)
        os.makedirs(env_src, exist_ok=True)
        with open(os.path.join(env_src, "manifest.json"), "w", encoding="utf-8") as f:
            f.write('{"version": 1}')
        self.assertFalse(mod.can_reuse_staged_env(env_src, env_dst))
        with open(os.path.join(env_dst, "manifest.json"), "w", encoding="utf-8") as f:
            f.write('{"version": 1}')
        with open(os.path.join(env_dst, "bootstrap", "bootstrap.tar.gz"), "wb") as f:
            f.write(b"tgz")
        self.assertTrue(mod.can_reuse_staged_env(env_src, env_dst))
        with open(os.path.join(env_src, "manifest.json"), "w", encoding="utf-8") as f:
            f.write('{"version": 2}')
        self.assertFalse(mod.can_reuse_staged_env(env_src, env_dst))
        self.assertFalse(mod.can_reuse_staged_runtime(os.path.join(self.tmp_dir, "missing_runtime"), os.path.dirname(os.path.dirname(BUILDER_DIR))))


if __name__ == "__main__":
    unittest.main()
