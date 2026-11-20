"""Offline tests for tools/smoke/audit-arm64-msi.py."""
from __future__ import annotations

import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

MODULE_PATH = Path(__file__).with_name("audit-arm64-msi.py")
SPEC = importlib.util.spec_from_file_location("audit_arm64_msi", MODULE_PATH)
assert SPEC and SPEC.loader
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


def fake_pe(machine: int) -> bytes:
    header = bytearray(0x80)
    header[0:2] = b"MZ"
    struct.pack_into("<I", header, 0x3C, 0x40)
    header[0x40:0x44] = b"PE\x00\x00"
    struct.pack_into("<H", header, 0x44, machine)
    return bytes(header)


def build_synthetic_tree(
    root: Path,
    *,
    exe_machine: int = 0xAA64,
    tess_machine: int = 0xAA64,
    include_x64_ocr: bool = False,
    sable_line: str = "java-options=-Dcoach.sableLogin=true",
    tamper_manifest: bool = False,
) -> Path:
    app_root = root / "LearningCoach"
    (app_root / "runtime" / "bin" / "server").mkdir(parents=True)
    (app_root / "app").mkdir(parents=True)
    (app_root / "LearningCoach.exe").write_bytes(fake_pe(exe_machine))
    (app_root / "runtime" / "bin" / "server" / "jvm.dll").write_bytes(fake_pe(0xAA64))
    (app_root / "app" / "LearningCoach.cfg").write_text(f"[JavaOptions]\n{sable_line}\n", encoding="utf-8")

    with zipfile.ZipFile(app_root / "app" / "skiko-awt-runtime-windows-arm64-0.1.jar", "w") as jar:
        jar.writestr("skiko-windows-arm64.dll", fake_pe(0xAA64))

    ocr_files = {
        "libtesseract55.dll": fake_pe(tess_machine),
        "libleptonica-1.87.0.dll": fake_pe(0xAA64),
        "tessdata/eng.traineddata": b"eng-model",
    }
    ocr_zip_path = root / "windows-arm64.zip"
    with zipfile.ZipFile(ocr_zip_path, "w") as zf:
        for name, data in ocr_files.items():
            zf.writestr(name, data)
    manifest_lines = []
    for name, data in sorted(ocr_files.items()):
        digest = hashlib.sha256(data).hexdigest()
        if tamper_manifest and name == "tessdata/eng.traineddata":
            digest = "0" * 64
        manifest_lines.append(f"{digest}  {name}")

    with zipfile.ZipFile(app_root / "app" / "desktopApp-1.1.0.jar", "w") as jar:
        jar.writestr("ocr/windows-arm64.zip", ocr_zip_path.read_bytes())
        jar.writestr("ocr/sha256.txt", "\n".join(manifest_lines) + "\n")
        if include_x64_ocr:
            jar.writestr("ocr/windows-x64.zip", b"not-allowed")

    return root


class AuditArm64MsiTest(unittest.TestCase):
    def test_valid_arm64_tree_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tree = build_synthetic_tree(Path(tmp))
            lines = audit.audit_extracted_root(tree, sable_login="true", forbid_private_config=True)
            self.assertIn("standalone_pe_aa64=2/2", lines)
            self.assertIn("skiko-windows-arm64.dll=AA64", lines)
            self.assertIn("ocr/libtesseract55.dll=AA64", lines)
            self.assertIn("ocr_manifest_verified=3/3", lines)
            self.assertIn("sable_login_cfg=true", lines)

    def test_rejects_x64_launcher(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tree = build_synthetic_tree(Path(tmp), exe_machine=0x8664)
            with self.assertRaisesRegex(AssertionError, "LearningCoach.exe.*AMD64"):
                audit.audit_extracted_root(tree, sable_login="true")

    def test_rejects_x64_ocr_dll_or_archive(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tree = build_synthetic_tree(Path(tmp), tess_machine=0x8664)
            with self.assertRaisesRegex(AssertionError, "libtesseract55.dll.*AMD64"):
                audit.audit_extracted_root(tree, sable_login="true")
        with tempfile.TemporaryDirectory() as tmp:
            tree = build_synthetic_tree(Path(tmp), include_x64_ocr=True)
            with self.assertRaisesRegex(AssertionError, "windows-x64.zip"):
                audit.audit_extracted_root(tree, sable_login="true")

    def test_rejects_missing_sable_flag_and_manifest_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tree = build_synthetic_tree(Path(tmp), sable_line="java-options=-Dcoach.sableLogin=false")
            with self.assertRaisesRegex(AssertionError, "sableLogin=true"):
                audit.audit_extracted_root(tree, sable_login="true")
        with tempfile.TemporaryDirectory() as tmp:
            tree = build_synthetic_tree(Path(tmp), tamper_manifest=True)
            with self.assertRaisesRegex(AssertionError, "OCR digest mismatch"):
                audit.audit_extracted_root(tree, sable_login="true")


if __name__ == "__main__":
    unittest.main()
