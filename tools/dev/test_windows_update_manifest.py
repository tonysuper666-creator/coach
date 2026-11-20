"""The published manifest is shared by Windows and Android; the APK entry must be well formed."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "windows-update-manifest.py"


def run(directory: Path, version: str, *extra: str):
    return subprocess.run(
        [sys.executable, str(SCRIPT), str(directory), version, *extra],
        capture_output=True, text=True, check=False,
    )


class UpdateManifest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="update-manifest-")
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def stage(self, name: str, payload: bytes):
        (self.root / name).write_bytes(payload)

    def manifest(self):
        return json.loads((self.root / "latest.json").read_text(encoding="utf-8"))

    def test_windows_only_release_offers_no_android_package(self):
        self.stage("LearningCoach-1.1.0-x64.msi", b"msi-x64")
        done = run(self.root, "1.1.0")
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual(["x64"], [entry["arch"] for entry in self.manifest()["packages"]])

    def test_android_package_carries_the_version_code_the_installer_compares(self):
        self.stage("LearningCoach-1.1.0-x64.msi", b"msi-x64")
        self.stage("LearningCoach-1.1.0-android.apk", b"apk-bytes")
        done = run(self.root, "1.1.0", "--version-code", "3")
        self.assertEqual(0, done.returncode, done.stderr)
        android = [entry for entry in self.manifest()["packages"] if entry["arch"] == "android"]
        self.assertEqual(1, len(android))
        self.assertEqual("1.1.0", android[0]["version"])
        self.assertEqual(3, android[0]["versionCode"])
        self.assertEqual("LearningCoach-1.1.0-android.apk", android[0]["file"])
        self.assertEqual(9, android[0]["size"])
        self.assertRegex(android[0]["sha256"], r"^[a-f0-9]{64}$")

    def test_android_package_without_version_code_is_rejected(self):
        self.stage("LearningCoach-1.1.0-android.apk", b"apk")
        done = run(self.root, "1.1.0")
        self.assertNotEqual(0, done.returncode)
        self.assertIn("--version-code", done.stderr)
        self.assertFalse((self.root / "latest.json").exists())

    def test_rejects_a_version_that_cannot_be_published_on_both_platforms(self):
        self.stage("LearningCoach-256.0.0-android.apk", b"apk")
        done = run(self.root, "256.0.0", "--version-code", "3")
        self.assertNotEqual(0, done.returncode)
        self.assertIn("limits", done.stderr)


if __name__ == "__main__":
    unittest.main()
