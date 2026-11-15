#!/usr/bin/env python3
"""Verify explicit release inputs; optionally copy them to a NEW offline staging tree.

Never signs, generates keys, installs, launches components, or discovers credentials.
The approved fingerprint MUST come from an independent release approval record.
This is a delivery tool, not a replacement for WindowsBookRuntime.installed().
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile

IDENTITY = {
    "contract": "windows-owned-book-runtime-v1",
    "provider": "local-codex-subscription-cli",
    "model": "gpt-6.1-sol",
    "notice": "owned-book-excerpts-v1",
}
NAMES = ("codex.exe", "coach-iso.exe", "coach-auth.exe")
ED25519_SPKI = bytes.fromhex("302a300506032b6570032100")


def validate_public_key(key):
    """Canonical on-curve, non-small-order Ed25519 point; NOT release approval.

    RFC 8032 section 5.1.3 decoding, followed by three doublings to reject
    every point killed by the cofactor 8. OpenSSL still verifies the signature.
    In particular, zero encoding is order 4; 01 followed by zeros is identity.
    Some signature providers accept zero-key/zero-signature combinations.
    """
    if len(key) != 44 or not key.startswith(ED25519_SPKI):
        raise ValueError("expected canonical X.509 DER Ed25519 public key")
    p = 2**255 - 19
    d = -121665 * pow(121666, p - 2, p) % p
    encoded = int.from_bytes(key[12:], "little")
    y, sign = encoded & (2**255 - 1), encoded >> 255
    if y >= p:
        raise ValueError("non-canonical Ed25519 public point")
    denominator = (d * y * y + 1) % p
    if denominator == 0:
        raise ValueError("invalid Ed25519 public point")
    x2 = (y * y - 1) * pow(denominator, p - 2, p) % p
    x = pow(x2, (p + 3) // 8, p)
    if x * x % p != x2:
        x = x * pow(2, (p - 1) // 4, p) % p
    if x * x % p != x2 or (x == 0 and sign):
        raise ValueError("invalid Ed25519 public point")
    if x & 1 != sign:
        x = p - x
    # Complete Edwards doubling for a=-1. Denominators are nonzero on curve.
    for _ in range(3):
        product = d * x * x * y * y % p
        x, y = (2 * x * y * pow((1 + product) % p, p - 2, p) % p,
                (y * y + x * x) * pow((1 - product) % p, p - 2, p) % p)
    if (x, y) == (0, 1):
        raise ValueError("small-order Ed25519 public key is not a release trust anchor")


def plain_path(path, directory=False):
    """Reject links/reparse points in explicit inputs and their parent chain."""
    path = Path(os.path.abspath(path))
    for part in (path, *path.parents):
        info = part.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("link/reparse point in input or destination path")
    info = path.stat()
    if not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)):
        raise ValueError("input has unexpected file type")
    return path


def bounded(path, limit):
    with plain_path(path).open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError("input exceeds contract size")
    return data


def digest(path):
    h = hashlib.sha256()
    with plain_path(path).open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(data)
    return h.hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--public-key", required=True, type=Path)
    parser.add_argument("--approved-key-sha256", required=True)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--signature", required=True, type=Path)
    parser.add_argument("--codex", required=True, type=Path)
    parser.add_argument("--isolation", required=True, type=Path)
    parser.add_argument("--auth-helper", required=True, type=Path)
    parser.add_argument("--stage", type=Path, help="new, non-existing offline directory")
    parser.add_argument("--openssl", default="openssl", help="OpenSSL supporting Ed25519 pkeyutl")
    args = parser.parse_args()

    if not re.fullmatch("[a-f0-9]{64}", args.approved_key_sha256):
        raise ValueError("approved fingerprint must be a lowercase SHA-256 from an independent approval")
    key = bounded(args.public_key, 1024)
    if hashlib.sha256(key).hexdigest() != args.approved_key_sha256:
        raise ValueError("public key does not match independently approved fingerprint")
    validate_public_key(key)
    manifest = bounded(args.manifest, 8192)
    signature = bounded(args.signature, 64)
    if len(signature) != 64:
        raise ValueError("detached Ed25519 signature must be exactly 64 bytes")

    # Verify snapshots of public metadata, not mutable paths supplied to OpenSSL.
    with tempfile.TemporaryDirectory(prefix="coach-public-signature-") as tmp:
        tmp = Path(tmp)
        for name, data in (("key.der", key), ("manifest.json", manifest), ("manifest.sig", signature)):
            (tmp / name).write_bytes(data)
        checked = subprocess.run(
            [args.openssl, "pkeyutl", "-verify", "-rawin", "-pubin", "-keyform", "DER",
             "-inkey", str(tmp / "key.der"), "-sigfile", str(tmp / "manifest.sig"),
             "-in", str(tmp / "manifest.json")], capture_output=True, check=False,
        )
        if checked.returncode != 0:
            raise ValueError("Ed25519 signature verification failed; nothing staged")

    data = json.loads(manifest.decode("utf-8"), object_pairs_hook=unique_object)
    if not isinstance(data, dict) or set(data) != {*IDENTITY, "sha256"}:
        raise ValueError("manifest keys differ from production contract")
    if any(data[key] != value for key, value in IDENTITY.items()):
        raise ValueError("runtime identity differs from production contract")
    hashes = data["sha256"]
    if not isinstance(hashes, dict) or set(hashes) != set(NAMES):
        raise ValueError("manifest must contain exactly the three production components")
    if any(not isinstance(h, str) or not re.fullmatch("[a-f0-9]{64}", h) for h in hashes.values()):
        raise ValueError("invalid component digest")
    inputs = dict(zip(NAMES, (args.codex, args.isolation, args.auth_helper)))
    for name, path in inputs.items():
        if digest(path) != hashes[name]:
            raise ValueError("component hash mismatch: " + name)

    if args.stage:
        destination = Path(os.path.abspath(args.stage))
        plain_path(destination.parent, directory=True)
        destination.mkdir()  # No overwrite, deletion, install, or production resources mutation.
        classpath = destination / "classpath"
        runtime = destination / "resources" / "windows-book-runtime"
        classpath.mkdir()
        runtime.mkdir(parents=True)
        (classpath / "windows-book-runtime-trust.der").write_bytes(key)
        (runtime / "manifest.json").write_bytes(manifest)
        (runtime / "manifest.sig").write_bytes(signature)
        for name, path in inputs.items():
            shutil.copyfile(plain_path(path), runtime / name)
            if digest(runtime / name) != hashes[name]:
                # Retain failed staging for review; never install or remove someone else's files.
                raise ValueError("staged component changed; discard this staging tree: " + name)
    print(json.dumps({"result": "verified", "staged": bool(args.stage),
        "publicKeySha256": args.approved_key_sha256,
        "manifestSha256": hashlib.sha256(manifest).hexdigest(), "sha256": hashes,
        "applicationClasspathChanged": False, "componentLaunches": 0}, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, TypeError) as error:
        print(json.dumps({"result": "refused", "reason": str(error)}), file=sys.stderr)
        sys.exit(2)
