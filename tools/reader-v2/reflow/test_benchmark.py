"""Measurement integrity checks; no textbook fixtures or network access."""
import argparse
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import benchmark


class MeasurementTests(unittest.TestCase):
    def test_whitespace_is_not_silently_normalized(self):
        diff = benchmark.changed_chars("alpha\n\nbeta", "alpha\nbeta")
        self.assertEqual(diff["deleted"], 1)
        self.assertEqual(diff["changed"], 1)

    def test_unicode_counts_are_codepoints_not_utf16_units(self):
        diff = benchmark.changed_chars("a\U0001f600b", "ab")
        self.assertEqual(diff["deleted"], 1)
        self.assertEqual(diff["changed"], 1)

    def test_each_replacement_contributes_its_larger_side(self):
        diff = benchmark.changed_chars("aa|b", "x|yyy")
        self.assertEqual(diff["replaced_source"], 3)
        self.assertEqual(diff["replaced_result"], 4)
        self.assertEqual(diff["changed"], 5)

    def test_checkout_output_rejected_before_launching_java(self):
        args = argparse.Namespace(out=benchmark.REPO / "private-output")
        with patch("benchmark.subprocess.run") as launch:
            with self.assertRaisesRegex(ValueError, "outside the repository"):
                benchmark.run(args)
            launch.assert_not_called()

    def test_symlink_into_checkout_is_also_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            link = Path(directory) / "checkout"
            link.symlink_to(benchmark.REPO, target_is_directory=True)
            with self.assertRaises(ValueError):
                benchmark.outside_repo(link / "private-output")

    def test_old_docling_files_do_not_become_new_measurements(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "mock-input.bin"
            source.write_bytes(b"mock subprocess input, not a PDF fixture")
            output = root / "out"
            (output / "docling").mkdir(parents=True)
            # Deliberately invalid: a no-B run must not even parse this old file.
            (output / "docling" / "00001.json").write_text("stale", encoding="utf-8")
            # A Unicode line separator inside a JSON string is legal and is
            # distinct from the newline that terminates a probe record.
            row = {"page": 1, "text": "a\u2028\U0001f600b", "a_seconds": 0.01,
                   "c_seconds": 0.02, "regions": [], "fallback": False,
                   "clip_unverified": False, "rotated_glyphs": False}

            def fake_process(command, **kwargs):
                if command[0] == "java":
                    kwargs["stdout"].write(json.dumps(row, ensure_ascii=False) + "\n")

            args = argparse.Namespace(out=output, pdf=source, pages="1",
                                      pdfbox_classpath="mock", models_dir=None,
                                      review=None, sheets=False)
            with patch("benchmark.subprocess.run", side_effect=fake_process), contextlib.redirect_stdout(io.StringIO()):
                benchmark.run(args)
            receipt = json.loads((output / "metrics.json").read_text(encoding="utf-8"))
            self.assertIsNone(receipt["pages"][0]["B"])
            self.assertEqual(receipt["docling"], {})
            self.assertEqual(receipt["pages"][0]["source_codepoints"], 4)
            self.assertEqual(receipt["pages"][0]["source_utf16_units"], 5)


if __name__ == "__main__":
    unittest.main()
