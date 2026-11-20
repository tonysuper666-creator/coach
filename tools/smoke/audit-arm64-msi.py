"""Audit an extracted or packaged Windows ARM64 MSI for native AA64 binaries, OCR payload, and launcher flags."""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import zipfile

IMAGE_FILE_MACHINE_ARM64 = 0xAA64
MACHINE_NAMES = {
    0xAA64: "AA64",
    0x8664: "AMD64",
    0x014C: "I386",
}


def pe_machine(data: bytes, label: str) -> int:
    if len(data) < 0x40 or data[:2] != b"MZ":
        raise ValueError(f"{label}: not a PE image (missing MZ header)")
    (pe_offset,) = struct.unpack_from("<I", data, 0x3C)
    if pe_offset + 6 > len(data) or data[pe_offset : pe_offset + 4] != b"PE\x00\x00":
        raise ValueError(f"{label}: invalid PE signature")
    (machine,) = struct.unpack_from("<H", data, pe_offset + 4)
    return machine


def machine_name(machine: int) -> str:
    return MACHINE_NAMES.get(machine, f"0x{machine:04X}")


def assert_aa64(data: bytes, label: str) -> None:
    machine = pe_machine(data, label)
    if machine != IMAGE_FILE_MACHINE_ARM64:
        raise AssertionError(f"{label}: expected AA64 (0xAA64), got {machine_name(machine)}")


def audit_extracted_root(
    root: Path,
    sable_login: str | None = None,
    forbid_private_config: bool = False,
) -> list[str]:
    root = root.resolve()
    if not root.is_dir():
        raise AssertionError(f"Extracted directory does not exist: {root}")

    # Find the application root containing LearningCoach.exe
    exes = sorted(root.rglob("LearningCoach.exe"))
    if len(exes) != 1:
        raise AssertionError(f"Expected exactly one LearningCoach.exe under {root}, found {len(exes)}")
    app_root = exes[0].parent
    jvm_dll = app_root / "runtime" / "bin" / "server" / "jvm.dll"
    if not jvm_dll.is_file():
        raise AssertionError(f"Missing runtime/bin/server/jvm.dll under {app_root}")

    pe_files = sorted(
        p for p in app_root.rglob("*") if p.is_file() and p.suffix.lower() in {".exe", ".dll"}
    )
    if not pe_files:
        raise AssertionError("No standalone PE files found in MSI")
    skiko_arm64_checked = False
    for pe_file in pe_files:
        rel = pe_file.relative_to(app_root).as_posix()
        assert_aa64(pe_file.read_bytes(), rel)
        lower_name = pe_file.name.lower()
        if lower_name == "skiko-windows-x64.dll":
            raise AssertionError(f"Unexpected standalone skiko-windows-x64.dll: {rel}")
        if lower_name == "skiko-windows-arm64.dll":
            skiko_arm64_checked = True

    cfg = app_root / "app" / "LearningCoach.cfg"
    if not cfg.is_file():
        raise AssertionError(f"Missing launcher config: {cfg}")
    cfg_text = cfg.read_text(encoding="utf-8")
    if sable_login is not None:
        expected_opt = f"java-options=-Dcoach.sableLogin={sable_login}"
        if expected_opt not in cfg_text.splitlines():
            raise AssertionError(f"Missing {expected_opt!r} in app/LearningCoach.cfg")
    if forbid_private_config and "-Dcoach.friendUrl=" in cfg_text:
        raise AssertionError("Unexpected private -Dcoach.friendUrl in public build app/LearningCoach.cfg")

    app_dir = app_root / "app"
    jars = sorted(app_dir.glob("*.jar"))
    if not jars:
        raise AssertionError(f"No jars found under {app_dir}")

    ocr_checked = False
    ocr_entry_count = 0

    for jar_path in jars:
        if "skiko-awt-runtime-windows-x64" in jar_path.name:
            raise AssertionError(f"Unexpected x64 Skiko jar packaged: {jar_path.name}")
        with zipfile.ZipFile(jar_path) as jar:
            names = set(jar.namelist())
            if "skiko-windows-x64.dll" in names:
                raise AssertionError(f"Unexpected skiko-windows-x64.dll in {jar_path.name}")
            if "skiko-windows-arm64.dll" in names:
                assert_aa64(jar.read("skiko-windows-arm64.dll"), f"{jar_path.name}!skiko-windows-arm64.dll")
                skiko_arm64_checked = True
            if "ocr/windows-x64.zip" in names:
                raise AssertionError(f"Unexpected ocr/windows-x64.zip in {jar_path.name}")
            if "ocr/windows-arm64.zip" in names:
                if "ocr/sha256.txt" not in names:
                    raise AssertionError(f"Missing ocr/sha256.txt alongside ocr/windows-arm64.zip in {jar_path.name}")
                manifest_lines = [
                    line.strip()
                    for line in jar.read("ocr/sha256.txt").decode("utf-8").splitlines()
                    if line.strip()
                ]
                expected_hashes: dict[str, str] = {}
                for line in manifest_lines:
                    digest, _, name = line.partition("  ")
                    if len(digest) != 64 or not name:
                        raise AssertionError(f"Malformed ocr/sha256.txt line: {line!r}")
                    expected_hashes[name] = digest.lower()
                if not expected_hashes:
                    raise AssertionError("Empty ocr/sha256.txt")

                with tempfile.SpooledTemporaryFile() as buf:
                    buf.write(jar.read("ocr/windows-arm64.zip"))
                    buf.seek(0)
                    with zipfile.ZipFile(buf) as ocr_zip:
                        actual_names = {info.filename for info in ocr_zip.infolist() if not info.is_dir()}
                        if actual_names != set(expected_hashes):
                            raise AssertionError(
                                f"OCR zip entries mismatch: extra={sorted(actual_names - set(expected_hashes))}, "
                                f"missing={sorted(set(expected_hashes) - actual_names)}"
                            )
                        for name, expected in sorted(expected_hashes.items()):
                            payload = ocr_zip.read(name)
                            actual = hashlib.sha256(payload).hexdigest().lower()
                            if actual != expected:
                                raise AssertionError(f"OCR digest mismatch for {name}: {actual} != {expected}")
                            if name.lower().endswith(".dll"):
                                assert_aa64(payload, f"ocr/windows-arm64.zip!{name}")
                for required_dll in ("libtesseract55.dll", "libleptonica-1.87.0.dll"):
                    if required_dll not in expected_hashes:
                        raise AssertionError(f"Missing required ARM64 OCR DLL in manifest: {required_dll}")
                ocr_checked = True
                ocr_entry_count = len(expected_hashes)

    if not skiko_arm64_checked:
        raise AssertionError("skiko-windows-arm64.dll not found in extracted MSI files or packaged app jars")
    if not ocr_checked:
        raise AssertionError("ocr/windows-arm64.zip not found in packaged app jars")

    return [
        f"standalone_pe_aa64={len(pe_files)}/{len(pe_files)}",
        "LearningCoach.exe=AA64",
        "runtime/bin/server/jvm.dll=AA64",
        "skiko-windows-arm64.dll=AA64",
        "ocr/libtesseract55.dll=AA64",
        "ocr/libleptonica-1.87.0.dll=AA64",
        f"ocr_manifest_verified={ocr_entry_count}/{ocr_entry_count}",
        "ocr_x64_absent=true",
        f"sable_login_cfg={sable_login if sable_login is not None else 'unchecked'}",
    ]


def extract_msi(msi_path: Path, target_dir: Path) -> None:
    if os.name != "nt":
        raise RuntimeError("--msi extraction requires Windows (msiexec /a)")
    msi_abs = msi_path.resolve()
    target_abs = target_dir.resolve()
    cmd = f'msiexec.exe /a "{msi_abs}" /qn TARGETDIR="{target_abs}"'
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"msiexec /a failed ({result.returncode}): {result.stdout}\n{result.stderr}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--msi", type=Path, help="Path to LearningCoach ARM64 MSI to extract and audit")
    group.add_argument("--extracted", type=Path, help="Path to an already-extracted MSI directory")
    parser.add_argument("--sable-login", choices=("true", "false"), help="Require -Dcoach.sableLogin=<val> in LearningCoach.cfg")
    parser.add_argument("--forbid-private-config", action="store_true", help="Fail if private -Dcoach.friendUrl= is embedded")
    parser.add_argument("--out", type=Path, help="Write audit summary lines to this UTF-8 file")
    args = parser.parse_args(argv)

    lines: list[str] = []
    if args.msi:
        msi_path = args.msi.resolve()
        if not msi_path.is_file():
            raise SystemExit(f"MSI not found: {msi_path}")
        digest = hashlib.sha256()
        size = 0
        with msi_path.open("rb") as fh:
            for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                digest.update(chunk)
                size += len(chunk)
        lines.append(f"msi={msi_path.name}")
        lines.append(f"msi_bytes={size}")
        lines.append(f"msi_sha256={digest.hexdigest()}")
        with tempfile.TemporaryDirectory(prefix="coach-arm64-audit-") as tmp:
            extract_dir = Path(tmp) / "extract"
            extract_dir.mkdir(parents=True, exist_ok=True)
            extract_msi(msi_path, extract_dir)
            lines.extend(audit_extracted_root(extract_dir, args.sable_login, args.forbid_private_config))
    else:
        lines.extend(audit_extracted_root(args.extracted, args.sable_login, args.forbid_private_config))

    report = "\n".join(lines) + "\n"
    sys.stdout.write(report)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(report, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
