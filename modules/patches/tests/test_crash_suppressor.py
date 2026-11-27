#!/usr/bin/env python3
"""
test_crash_suppressor.py - Unit tests for address-independent failfast CPU/atomic patching.
"""
import os
import sys
import unittest

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
PATCHES_ROOT = os.path.dirname(TESTS_DIR)
sys.path.insert(0, os.path.join(PATCHES_ROOT, "groups", "failfast"))

import crash_suppressor  # noqa: E402


class TestCrashSuppressor(unittest.TestCase):
    def _build_blob(self, cpu_seq: bytes, atomic_seq: bytes) -> bytearray:
        buf = bytearray(b"\x00" * 256)
        buf[0x10 : 0x10 + len(cpu_seq)] = cpu_seq
        buf[0x10 + 0x64 : 0x10 + 0x64 + len(crash_suppressor.CPU_EPILOGUE)] = crash_suppressor.CPU_EPILOGUE
        buf[0x90 : 0x90 + len(atomic_seq)] = atomic_seq
        return buf

    def test_01_patches_v234_and_v235_prefixes_preserving_adrp_add(self):
        v234_cpu = bytes.fromhex("b30701d00a60905268be40f9") + crash_suppressor.CPU_RAW_TAIL
        v234_atomic = bytes.fromhex("680401f008a12c91") + crash_suppressor.ATOMIC_RAW_TAIL
        v235_cpu = crash_suppressor.TARGET_CPU
        v235_atomic = crash_suppressor.TARGET_ATOMIC

        for label, cpu_seq, atomic_seq in (
            ("v2.34.0", v234_cpu, v234_atomic),
            ("v2.35.2", v235_cpu, v235_atomic),
        ):
            blob = self._build_blob(cpu_seq, atomic_seq)
            ok, info = crash_suppressor.apply(blob, allow_missing=False)
            self.assertTrue(ok, f"{label} apply failed: {info}")
            self.assertEqual(info["status"], "patched")
            self.assertEqual(blob[0x10 : 0x10 + len(crash_suppressor.REPLACEMENT_CPU)], crash_suppressor.REPLACEMENT_CPU)
            self.assertEqual(blob[0x90 : 0x98], atomic_seq[:8], f"{label} must preserve ADRP/ADD prefix")
            self.assertEqual(blob[0x98 : 0x90 + 28], crash_suppressor.ATOMIC_PATCHED_TAIL)

            ok_v, info_v = crash_suppressor.apply(blob, verify=True, allow_missing=False)
            self.assertTrue(ok_v, f"{label} verify failed: {info_v}")
            self.assertEqual(info_v["status"], "verified")

            ok_r, info_r = crash_suppressor.apply(blob, allow_missing=False)
            self.assertTrue(ok_r)
            self.assertEqual(info_r["status"], "already_patched")

    def test_02_rejects_duplicate_hits_fail_closed(self):
        blob = self._build_blob(crash_suppressor.TARGET_CPU, crash_suppressor.TARGET_ATOMIC)
        blob.extend(b"\x00" * 32)
        blob.extend(crash_suppressor.TARGET_ATOMIC)
        ok, info = crash_suppressor.apply(blob, allow_missing=False)
        self.assertFalse(ok)
        self.assertTrue(info["status"].startswith("unexpected_count_"), info)

    def test_03_missing_signature_respects_allow_missing(self):
        blob = bytearray(b"\x00" * 128)
        ok_fail, info_fail = crash_suppressor.apply(blob, allow_missing=False)
        self.assertFalse(ok_fail)
        self.assertEqual(info_fail["status"], "missing_signature")

        ok_skip, info_skip = crash_suppressor.apply(blob, allow_missing=True)
        self.assertTrue(ok_skip)
        self.assertEqual(info_skip["status"], "skipped_missing")


if __name__ == "__main__":
    unittest.main()
