"""Write latest.json for the two versioned Windows MSIs in a staging directory."""

import argparse
import hashlib
import json
import re
from pathlib import Path
from tempfile import NamedTemporaryFile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()
    if not re.fullmatch(r"[1-9][0-9]{0,2}\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,4})", args.version):
        parser.error("expected a three-part MSI version")
    major, minor, patch = map(int, args.version.split("."))
    if major > 255 or minor > 255 or patch > 65535:
        parser.error("version exceeds MSI limits")

    packages = []
    for arch in ("x64", "arm64"):
        path = args.directory / f"LearningCoach-{args.version}-{arch}.msi"
        with path.open("rb") as package:
            digest = hashlib.sha256()
            size = 0
            for block in iter(lambda: package.read(1024 * 1024), b""):
                digest.update(block)
                size += len(block)
        if not 0 < size <= 2 * 1024**3:
            parser.error(f"invalid size for {path.name}")
        packages.append(dict(version=args.version, arch=arch, file=path.name, size=size, sha256=digest.hexdigest()))

    temporary = NamedTemporaryFile(dir=args.directory, mode="w", encoding="utf-8", delete=False)
    staged = Path(temporary.name)
    try:
        with temporary:
            json.dump({"packages": packages}, temporary, indent=2)
            temporary.write("\n")
        staged.replace(args.directory / "latest.json")
    finally:
        staged.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
