import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from . import ton1193_data_snapshot as snapshot
from .ton1175_verify import GateError


class DataSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.data = Path(self.temp.name).resolve() / 'data'
        self.data.mkdir()
        (self.data / 'coach.sqlite').write_bytes(b'test database')
        (self.data / 'blobs').mkdir()
        (self.data / 'blobs' / 'one').write_bytes(b'synthetic')
        self.nonce = 'a' * 32

    def test_real_clone_is_independent_and_fingerprinted(self):
        digest = snapshot.clone_snapshot(self.data, self.nonce)
        clone = self.data.parent / ('data.pre-' + self.nonce)
        self.assertEqual(digest, snapshot.fingerprint(clone))
        (self.data / 'coach.sqlite').write_bytes(b'changed')
        self.assertEqual(digest, snapshot.fingerprint(clone))
        self.assertNotEqual(digest, snapshot.fingerprint(self.data))

    def test_unavailable_clone_does_not_copy(self):
        with patch.object(snapshot, '_clone_fd', side_effect=GateError('clonefile unavailable')):
            with self.assertRaises(GateError):
                snapshot.clone_snapshot(self.data, self.nonce)
        self.assertFalse((self.data.parent / ('data.pre-' + self.nonce)).exists())
        self.assertEqual((self.data / 'coach.sqlite').read_bytes(), b'test database')

    def test_existing_snapshot_is_not_overwritten(self):
        target = self.data.parent / ('data.pre-' + self.nonce)
        target.mkdir()
        (target / 'marker').write_text('keep')
        with self.assertRaises(GateError):
            snapshot.clone_snapshot(self.data, self.nonce)
        self.assertEqual((target / 'marker').read_text(), 'keep')

    def test_links_and_special_files_are_rejected(self):
        (self.data / 'link').symlink_to('coach.sqlite')
        with self.assertRaises(GateError):
            snapshot.clone_snapshot(self.data, self.nonce)
        (self.data / 'link').unlink()
        os.link(self.data / 'coach.sqlite', self.data / 'hardlink')
        with self.assertRaises(GateError):
            snapshot.clone_snapshot(self.data, self.nonce)

    def test_changed_clone_is_not_accepted(self):
        real_clone = snapshot._clone_fd

        def tamper(source, parent, name):
            real_clone(source, parent, name)
            (self.data.parent / name / 'coach.sqlite').write_bytes(b'tampered')

        with patch.object(snapshot, '_clone_fd', side_effect=tamper):
            with self.assertRaisesRegex(GateError, 'content changed'):
                snapshot.clone_snapshot(self.data, self.nonce)


if __name__ == '__main__':
    unittest.main()
