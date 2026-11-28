"""Write latest.json for the versioned packages staged in a directory.

Windows MSIs and the Android APK share one manifest, so both platforms are offered the same release.
Each package is one entry keyed by `arch`; a release may ship a subset, and devices whose arch has no
entry simply see no update instead of a broken one. Android entries also carry `versionCode` because
the installer compares it — a package that does not advance it would be refused by the system.

An older `LearningCoach-<version>-<arch>.msi` staged in the same directory is written into `previous`
automatically: for each arch, the newest version that is still older than this release. That entry
carries the file name (resolved next to latest.json) and the SHA-256 already used to verify installs.
`--previous-version` overrides the automatic choice.
"""

import argparse
import hashlib
import json
import re
from pathlib import Path
from tempfile import NamedTemporaryFile

VERSION = re.compile(r"[1-9][0-9]{0,2}\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,4})")
PACKAGE_NAME = re.compile(
    r"LearningCoach-(?P<version>[1-9][0-9]{0,2}\.(?:0|[1-9][0-9]{0,2})\.(?:0|[1-9][0-9]{0,4}))-(?P<arch>x64|arm64)\.msi"
)


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


def version_tuple(text: str):
    return tuple(map(int, text.split(".")))


def previous_entries(directory: Path, version: str, explicit):
    if explicit:
        if not VERSION.fullmatch(explicit):
            raise SystemExit("expected a three-part previous version")
        if version_tuple(explicit) >= version_tuple(version):
            raise SystemExit("previous version must be older than the release")
        found = []
        for arch in ("x64", "arm64"):
            path = directory / f"LearningCoach-{explicit}-{arch}.msi"
            if not path.exists():
                continue
            name, size, sha256 = package(path, 2 * 1024**3)
            found.append(dict(version=explicit, arch=arch, file=name, size=size, sha256=sha256))
        if not found:
            raise SystemExit(f"no LearningCoach-{explicit}-<arch>.msi in {directory}")
        return found

    chosen = {}
    for path in directory.iterdir():
        match = PACKAGE_NAME.fullmatch(path.name)
        if not match:
            continue
        older = match.group("version")
        if version_tuple(older) >= version_tuple(version):
            continue
        arch = match.group("arch")
        current = chosen.get(arch)
        if current is None or version_tuple(older) > version_tuple(current[0]):
            chosen[arch] = (older, path)
    found = []
    for arch in ("x64", "arm64"):
        picked = chosen.get(arch)
        if picked is None:
            continue
        older, path = picked
        name, size, sha256 = package(path, 2 * 1024**3)
        found.append(dict(version=older, arch=arch, file=name, size=size, sha256=sha256))
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("version")
    parser.add_argument("--version-code", type=int, help="Android versionCode paired with the APK")
    parser.add_argument(
        "--previous-version",
        help="publish this older version instead of the newest older installer staged beside the release",
    )
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

    try:
        previous = previous_entries(args.directory, args.version, args.previous_version)
    except SystemExit as error:
        parser.error(str(error))

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
