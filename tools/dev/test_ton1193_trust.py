import os
from pathlib import Path
import json
import tempfile
import unittest
import zipfile

from tools.dev import ton1193_capability
from tools.dev import ton1193_trust as trust
from tools.dev.ton1175_switch import GateError


class TrustTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.release = self.root / 'release'
        self.bin = self.release / 'tools/bridge/.venv/bin'
        self.bin.mkdir(parents=True)
        self.cellar = self.root / 'Cellar'
        self.cellar.mkdir()
        self.interpreter = self.cellar / 'python3.15'
        self.interpreter.write_bytes(b'fixture interpreter')
        (self.bin / 'python3.15').symlink_to(self.interpreter)
        (self.bin / 'python').symlink_to('python3.15')

    def snapshot(self, value=None, text='python3.15'):
        return trust.python_leaf_snapshot(str(self.release), value or str(self.bin / 'python'),
                                          text, self.cellar)

    def test_venv_link_is_bound_and_replacement_changes_snapshot(self):
        before = self.snapshot()
        self.assertEqual(before[1][2], 'python3.15')
        self.assertEqual(before[2][0], str(self.interpreter))
        (self.bin / 'python').rename(self.bin / 'original-python')
        (self.bin / 'python').symlink_to('python3.15')
        self.assertNotEqual(before, self.snapshot())

    def test_bad_path_link_or_target_are_rejected(self):
        with self.assertRaises(GateError):
            self.snapshot(str(self.bin / '../bin/python'))
        with self.assertRaises(GateError):
            self.snapshot(text='python3')
        self.interpreter.unlink()
        outside = self.root / 'outside'
        outside.write_bytes(b'outside')
        (self.bin / 'python3.15').unlink()
        (self.bin / 'python3.15').symlink_to(outside)
        with self.assertRaises(GateError):
            self.snapshot()

    def test_symlink_parent_or_group_writable_directory_rejected(self):
        parent = self.bin.parent
        self.bin.rename(parent / 'real-bin')
        self.bin.symlink_to('real-bin')
        with self.assertRaises(GateError):
            self.snapshot()
        self.bin.unlink()
        (parent / 'real-bin').rename(self.bin)
        os.chmod(self.bin, 0o775)
        with self.assertRaises(GateError):
            self.snapshot()

    def test_toolchain_hash_changes_and_root_escape_rejected(self):
        before = trust.file_snapshot(self.interpreter, self.cellar)
        self.interpreter.write_bytes(b'changed interpreter')
        self.assertNotEqual(before, trust.file_snapshot(self.interpreter, self.cellar))
        with self.assertRaises(GateError):
            trust.file_snapshot(self.interpreter, self.release)

    def test_protocol_requires_agreement_of_head_and_installed_classes(self):
        lib = self.release / 'app/lib'
        lib.mkdir(parents=True)
        jar = lib / 'app.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('example.class', b'fixture')
        checkout = Path(__file__).resolve().parents[2]
        # Resolve the known old commit to the full object ID, as a real
        # installation record does.
        import subprocess
        old = subprocess.check_output(['git', '-C', str(checkout), 'rev-parse', '5ef9989'], text=True).strip()
        current = subprocess.check_output(['git', '-C', str(checkout), 'rev-parse', '50c95f9'], text=True).strip()
        self.assertEqual(trust.capability(checkout, self.release, old), {'coverageProtocol': 'absent'})
        with self.assertRaises(GateError):
            trust.capability(checkout, self.release, current)
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr(trust.PROTOCOL_CLASS, b'fixture')
        self.assertEqual(trust.capability(checkout, self.release, current), {'coverageProtocol': 'v1'})
        with self.assertRaises(GateError):
            trust.capability(checkout, self.release, old)

    def test_read_only_capability_requires_fixed_private_control_path(self):
        import subprocess
        checkout = Path(__file__).resolve().parents[2]
        head = subprocess.check_output(['git', '-C', str(checkout), 'rev-parse', '5ef9989'], text=True).strip()
        service = self.root / 'Coach/service'
        release = service / 'releases/old'
        lib = release / 'app/lib'
        lib.mkdir(parents=True)
        with zipfile.ZipFile(lib / 'app.jar', 'w') as jar:
            jar.writestr('fixture.class', b'fixture')
        installation = service / 'installation.json'
        installation.write_text(json.dumps({'root': str(release), 'sourceHead': head}))
        os.chmod(installation, 0o600)
        self.assertEqual(ton1193_capability.inspect_installation(checkout, self.root),
                         {'coverageProtocol': 'absent'})
        installation.write_text(json.dumps({'root': str(self.release), 'sourceHead': head}))
        with self.assertRaises(GateError):
            ton1193_capability.inspect_installation(checkout, self.root)
        installation.unlink()
        installation.symlink_to(self.root / 'elsewhere')
        with self.assertRaises(GateError):
            ton1193_capability.inspect_installation(checkout, self.root)


if __name__ == '__main__':
    unittest.main()
