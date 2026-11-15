"""Offline public-metadata regressions. No key generation or component execution."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1] / "assemble-runtime.py"
spec = importlib.util.spec_from_file_location("runtime_assembly", TOOL)
assembly = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assembly)

# RFC 8032 section 7.1 TEST 2: public data only, never an application trust root.
RFC_KEY = bytes.fromhex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
RFC_SIG = bytes.fromhex(
    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
    "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")


class PublicPointTest(unittest.TestCase):
    def test_rfc_public_point_and_published_signature(self):
        key = assembly.ED25519_SPKI + RFC_KEY
        assembly.validate_public_key(key)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, data in (("key", key), ("message", b"\x72"), ("sig", RFC_SIG)):
                (root / name).write_bytes(data)
            result = subprocess.run(["openssl", "pkeyutl", "-verify", "-rawin", "-pubin",
                "-keyform", "DER", "-inkey", str(root / "key"), "-in", str(root / "message"),
                "-sigfile", str(root / "sig")], capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_all_eight_small_order_encodings(self):
        # y coordinates of the eight torsion points; nonzero x has both signs.
        ys = [(0, (0, 1)), (1, (0,)), (2**255 - 20, (0,)),
              (int.from_bytes(bytes.fromhex(
                  "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05"), "little"), (0, 1)),
              (int.from_bytes(bytes.fromhex(
                  "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a"), "little"), (0, 1))]
        for y, signs in ys:
            for sign in signs:
                with self.subTest(y=y, sign=sign), self.assertRaisesRegex(ValueError, "small-order"):
                    assembly.validate_public_key(assembly.ED25519_SPKI + (y | sign << 255).to_bytes(32, "little"))

    def test_noncanonical_and_off_curve(self):
        for encoding in ((2**255 - 19), (2**255 + 1), 2):
            with self.subTest(encoding=encoding), self.assertRaises(ValueError):
                assembly.validate_public_key(assembly.ED25519_SPKI + encoding.to_bytes(32, "little"))


class CommandBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="SYNTHETIC-runtime-boundary-")
        self.root = Path(self.tmp.name).resolve()  # macOS /var itself is a link.
        self.key = self.root / "key.der"
        self.key.write_bytes(assembly.ED25519_SPKI + RFC_KEY)
        self.manifest = self.root / "manifest.json"
        self.manifest.write_bytes(b"SYNTHETIC unsigned manifest")
        self.sig = self.root / "manifest.sig"
        self.sig.write_bytes(bytes(64))
        self.stage = self.root / "must-not-exist"

    def tearDown(self):
        self.tmp.cleanup()

    def invoke(self, **overrides):
        values = {"public-key": self.key, "approved-key-sha256": hashlib.sha256(self.key.read_bytes()).hexdigest(),
            "manifest": self.manifest, "signature": self.sig, "stage": self.stage,
            "codex": self.root / "NEVER-READ-codex", "isolation": self.root / "NEVER-READ-iso",
            "auth-helper": self.root / "NEVER-READ-auth"}
        values.update(overrides)
        args = [sys.executable, str(TOOL)]
        for name, value in values.items():
            args += ["--" + name, str(value)]
        result = subprocess.run(args, capture_output=True, text=True)
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertFalse(self.stage.exists(), result.stdout + result.stderr)
        self.assertEqual("refused", json.loads(result.stderr)["result"])
        return json.loads(result.stderr)["reason"]

    def test_zero_key_zero_signature_refuses_before_openssl_or_component_reads(self):
        self.key.write_bytes(assembly.ED25519_SPKI + bytes(32))
        reason = self.invoke(openssl=self.root / "NEVER-START-openssl")
        self.assertIn("small-order", reason)

    def test_identity_key_zero_signature_refuses(self):
        self.key.write_bytes(assembly.ED25519_SPKI + b"\x01" + bytes(31))
        self.assertIn("small-order", self.invoke())

    def test_missing_approval_directory(self):
        self.invoke(**{"public-key": self.root / "missing" / "key"})

    def test_wrong_der_with_honest_digest(self):
        self.key.write_bytes(b"SYNTHETIC not DER")
        self.assertIn("canonical", self.invoke())

    def test_wrong_and_uppercase_fingerprints(self):
        for pin in ("f" * 64, hashlib.sha256(self.key.read_bytes()).hexdigest().upper()):
            with self.subTest(pin=pin):
                self.invoke(**{"approved-key-sha256": pin})

    def test_signature_lengths_and_manifest_limit(self):
        for size in (63, 65):
            self.sig.write_bytes(bytes(size))
            self.invoke()
        self.sig.write_bytes(bytes(64))
        self.manifest.write_bytes(bytes(8193))
        self.assertIn("size", self.invoke())

    def test_linked_manifest(self):
        link = self.root / "linked.json"
        link.symlink_to(self.manifest)
        self.assertIn("link/reparse", self.invoke(manifest=link))

    def test_valid_point_does_not_authorize_an_unsigned_manifest(self):
        self.assertIn("signature verification failed", self.invoke())


if __name__ == "__main__":
    unittest.main()
