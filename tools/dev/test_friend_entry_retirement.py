import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from tools.dev import friend_entry_retirement as tool


class RetirementTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name).resolve()
        self.root = self.base / 'service'
        self.source = self.root / 'upgrade-example/control-old.py'
        self.source.parent.mkdir(parents=True)
        self.original = b"raise AssertionError('PRIVATE_SENTINEL_DO_NOT_EXECUTE')\n"
        self.source.write_bytes(self.original)
        self.source.chmod(0o755)
        self.selection = self.base / 'selection.json'
        self.output = self.base / 'dry-run'
        self.select([self.row()])

    def row(self, relative='upgrade-example/control-old.py'):
        return {'relative': relative, 'sha256': hashlib.sha256(self.original).hexdigest()}

    def select(self, rows):
        self.selection.write_text(json.dumps(rows))
        self.selection.chmod(0o600)

    def prepare(self):
        return tool.prepare(self.root, self.selection, self.output)

    def test_bundle_preserves_source_and_never_executes_code(self):
        before = self.source.stat()
        result = self.prepare()
        self.assertEqual(result['files'], 1)
        self.assertEqual(result['production_mutations'], 0)
        self.assertEqual(self.source.read_bytes(), self.original)
        self.assertEqual(self.source.stat().st_ino, before.st_ino)
        self.assertEqual(self.source.stat().st_mode, before.st_mode)
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o700)
        for name in ('plan.json', 'originals.zip', 'refusal.txt'):
            self.assertEqual((self.output / name).stat().st_mode & 0o777, 0o600)

    def test_refusal_blocks_direct_execution_and_import(self):
        self.prepare()
        stub = self.base / 'retired.py'
        stub.write_bytes((self.output / 'refusal.txt').read_bytes())
        for argv in ([sys.executable, str(stub)],
                     [sys.executable, '-c', "import runpy;runpy.run_path(__import__('sys').argv[1])", str(stub)]):
            result = subprocess.run(argv, capture_output=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn(b'Retired service entry', result.stderr)
        # An ordinary import also executes the top-level refusal.
        result = subprocess.run([sys.executable, '-c', 'import retired'], cwd=self.base, capture_output=True)
        self.assertEqual(result.returncode, 1)

    def test_rejects_changed_source_mode_or_inode(self):
        for change in ('bytes', 'mode', 'inode'):
            with self.subTest(change=change):
                self.output = self.base / change
                result = self.prepare()
                mode = self.source.stat().st_mode
                if change == 'bytes':
                    self.source.write_bytes(b'drift')
                elif change == 'mode':
                    self.source.chmod(0o600)
                else:
                    self.source.rename(self.source.with_suffix('.saved'))
                    self.source.write_bytes(self.original)
                with self.assertRaises(ValueError):
                    tool.verify(self.output / 'plan.json', result['plan_sha256'])
                self.source.write_bytes(self.original)
                self.source.chmod(mode)

    def test_rejects_traversal_live_files_and_release_payload(self):
        for relative in ('../outside.py', '/absolute.py', 'upgrade-example/../control.py',
                         'control.py', 'pair.py', 'releases/old/control.py',
                         'upgrade-example/coach.sqlite'):
            with self.subTest(relative=relative):
                self.select([self.row(relative)])
                with self.assertRaises((ValueError, OSError)):
                    self.prepare()
                self.assertFalse(self.output.exists())

    def test_rejects_symlink_duplicate_wrong_hash_or_public_selection(self):
        link = self.source.parent / 'linked.py'
        link.symlink_to(self.source)
        for rows in ([self.row('upgrade-example/linked.py')], [self.row(), self.row()],
                     [dict(self.row(), sha256='0' * 64)]):
            self.select(rows)
            with self.assertRaises(ValueError):
                self.prepare()
        self.select([self.row()])
        self.selection.chmod(0o644)
        with self.assertRaises(ValueError):
            self.prepare()

    def test_existing_output_and_output_inside_service_refused(self):
        self.output.mkdir()
        with self.assertRaises(ValueError):
            self.prepare()
        self.output = self.root / 'bundle'
        with self.assertRaises(ValueError):
            self.prepare()

    def test_refuses_plan_archive_and_stub_drift(self):
        result = self.prepare()
        for name in ('plan.json', 'originals.zip', 'refusal.txt'):
            path = self.output / name
            original = path.read_bytes()
            path.write_bytes(original + b' ')
            with self.assertRaises(ValueError):
                tool.verify(self.output / 'plan.json', result['plan_sha256'])
            path.write_bytes(original)

    def test_cli_failure_never_prints_private_data(self):
        self.select([dict(self.row(), sha256='0' * 64)])
        result = subprocess.run([sys.executable, str(Path(tool.__file__)), 'prepare',
                                 '--root', str(self.root), '--selection', str(self.selection),
                                 '--output', str(self.output)], capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, b'')
        self.assertEqual(result.stderr, b'RETIREMENT_DRY_RUN=FAIL\n')


if __name__ == '__main__':
    unittest.main()
