#!/usr/bin/env python3
"""Unit tests for the sandboxMode patch in web/patch_main.py.

Run: python3 -B web/tests/test_patch_main_sandbox.py
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import patch_main  # noqa: E402

W1A_UNPATCHED = (
    '/* Desktop Mode */\n'
    'function W1a(a){switch(a){'
    'case "use_global":return{sandboxMode:void 0,autoExecutionPolicy:X};'
    'case "request_review":return{sandboxMode:!1,autoExecutionPolicy:Y};'
    'case "vetted":return{sandboxMode:!1,autoExecutionPolicy:Z};'
    'case "auto":return{sandboxMode:!0,autoExecutionPolicy:A};'
    'case "turbo":return{sandboxMode:!1,autoExecutionPolicy:B};'
    'default:return{sandboxMode:!0,autoExecutionPolicy:C}}}'
)


def _run_patch_main(content):
    with tempfile.NamedTemporaryFile(
        "w", suffix=".js", delete=False, encoding="utf-8"
    ) as f:
        f.write(content)
        path = f.name
    try:
        ok = patch_main.patch_main(path)
        with open(path, encoding="utf-8") as f:
            return ok, f.read()
    finally:
        os.unlink(path)


class SandboxPatchTest(unittest.TestCase):
    def test_flips_auto_and_default_only(self):
        ok, out = _run_patch_main(W1A_UNPATCHED)
        self.assertTrue(ok)
        self.assertIn('case "auto":return{sandboxMode:!1', out)
        self.assertIn("default:return{sandboxMode:!1", out)
        self.assertNotIn("sandboxMode:!0", out)
        self.assertIn('case "use_global":return{sandboxMode:void 0', out)
        self.assertIn('case "request_review":return{sandboxMode:!1', out)
        self.assertIn('case "turbo":return{sandboxMode:!1', out)

    def test_idempotent(self):
        ok, out = _run_patch_main(W1A_UNPATCHED)
        self.assertTrue(ok)
        ok2, out2 = _run_patch_main(out)
        self.assertTrue(ok2)
        self.assertEqual(out, out2)

    def test_already_patched_is_ok(self):
        already = W1A_UNPATCHED.replace("sandboxMode:!0", "sandboxMode:!1")
        ok, out = _run_patch_main(already)
        self.assertTrue(ok)
        self.assertEqual(already, out)

    def test_desktop_marker_does_not_skip_sandbox(self):
        fixture = '/* Desktop Mode */\n' + W1A_UNPATCHED
        ok, out = _run_patch_main(fixture)
        self.assertTrue(ok)
        self.assertNotIn("sandboxMode:!0", out)
        self.assertIn('case "auto":return{sandboxMode:!1', out)

    def test_missing_or_ambiguous_pattern_leaves_content_untouched(self):
        missing_src = 'var x = "Desktop Mode";\n'
        patched, changed = patch_main._disable_desktop_sandbox(missing_src)
        self.assertFalse(changed)
        self.assertEqual(patched, missing_src)

        ambiguous_src = W1A_UNPATCHED + "\n" + W1A_UNPATCHED
        patched2, changed2 = patch_main._disable_desktop_sandbox(ambiguous_src)
        self.assertFalse(changed2)
        self.assertEqual(patched2, ambiguous_src)

        # Graceful fallback through patch_main() leaves unmatched bundle untouched and returns True.
        ok, out = _run_patch_main(missing_src)
        self.assertTrue(ok)
        self.assertEqual(out, missing_src)


if __name__ == "__main__":
    unittest.main(verbosity=2)
