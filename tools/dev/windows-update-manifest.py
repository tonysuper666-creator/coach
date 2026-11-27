"""Write latest.json for the versioned packages staged in a directory.

Windows MSIs and the Android APK share one manifest, so both platforms are offered the same release.
Each package is one entry keyed by `arch`; a release may ship a subset, and devices whose arch has no
entry simply see no update instead of a broken one. Android entries also carry `versionCode` because
the installer compares it — a package that does not advance it would be refused by the system.
"""

import argparse
import hashlib
import json
import re
from pathlib import Path
from tempfile import NamedTemporaryFile

VERSION = re.compile(r"[1-9][0-9]{0,2}\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,4})")


def package(path: Path, limit: int):
    with path.open("rb") as file:
        digest = hashlib.sha256()
        size = 0
        for block in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(block)
            size += len(block)
    if not 0 < size <= limit:
        raise SystemExit(f"invalid size for {path.name}")
    return path.name, size, digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("version")
    parser.add_argument("--version-code", type=int, help="Android versionCode paired with the APK")
    parser.add_argument("--previous-version", help="also publish this older version as the one-step rollback package")
    args = parser.parse_args()
    if not VERSION.fullmatch(args.version):
        parser.error("expected a three-part release version")
    major, minor, patch = map(int, args.version.split("."))
    if major > 255 or minor > 255 or patch > 65535:
        parser.error("version exceeds the shared desktop/Android limits")

    packages = []
    for arch in ("x64", "arm64"):
        path = args.directory / f"LearningCoach-{args.version}-{arch}.msi"
        if not path.exists():
            continue
        name, size, sha256 = package(path, 2 * 1024**3)
        packages.append(dict(version=args.version, arch=arch, file=name, size=size, sha256=sha256))

    apk = args.directory / f"LearningCoach-{args.version}-android.apk"
    if apk.exists():
        if args.version_code is None:
            parser.error("--version-code is required when the Android APK is staged")
        if not 1 <= args.version_code <= 2_100_000_000:
            parser.error("Android versionCode must be in 1..2100000000")
        name, size, sha256 = package(apk, 1024**3)
        packages.append(dict(version=args.version, arch="android", versionCode=args.version_code,
                             file=name, size=size, sha256=sha256))

    if not packages:
        parser.error(f"no LearningCoach-{args.version}-<arch>.msi or -android.apk in {args.directory}")

    previous = []
    if args.previous_version:
        if not VERSION.fullmatch(args.previous_version):
            parser.error("expected a three-part previous version")
        if tuple(map(int, args.previous_version.split("."))) >= tuple(map(int, args.version.split("."))):
            parser.error("previous version must be older than the release")
        for arch in ("x64", "arm64"):
            path = args.directory / f"LearningCoach-{args.previous_version}-{arch}.msi"
            if not path.exists():
                continue
            name, size, sha256 = package(path, 2 * 1024**3)
            previous.append(dict(version=args.previous_version, arch=arch, file=name, size=size, sha256=sha256))
        if not previous:
            parser.error(f"no LearningCoach-{args.previous_version}-<arch>.msi in {args.directory}")

    payload = {"packages": packages}
    if previous:
        payload["previous"] = previous

    temporary = NamedTemporaryFile(dir=args.directory, mode="w", encoding="utf-8", delete=False)
    staged = Path(temporary.name)
    try:
        with temporary:
            json.dump(payload, temporary, indent=2)
            temporary.write("\n")
        staged.replace(args.directory / "latest.json")
    finally:
        staged.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
