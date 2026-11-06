"""The rollback contract is selected by the private installed descriptor, never by a passing commit."""
import json
from pathlib import Path
import tempfile
import unittest

from tools.dev.test_release_train import train


class InstalledContractTest(unittest.TestCase):
    def test_missing_mismatched_and_unreviewed_installations_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            descriptor = Path(temporary) / 'installation.json'
            with self.assertRaisesRegex(RuntimeError, 'explicit private'):
                train.installed_rollback(None, train.INSTALLED_ROLLBACK)
            descriptor.write_text(json.dumps(dict(sourceHead=train.INSTALLED_ROLLBACK)))
            self.assertEqual(train.installed_rollback(descriptor, train.INSTALLED_ROLLBACK), train.INSTALLED_ROLLBACK)
            with self.assertRaisesRegex(RuntimeError, 'differs from the installed'):
                train.installed_rollback(descriptor, '37f89eda20068e2197137b3d3f8aaa814fca5a7d')
            descriptor.write_text(json.dumps(dict(sourceHead='unreviewed')))
            with self.assertRaisesRegex(RuntimeError, 'no reviewed downgrade contract'):
                train.installed_rollback(descriptor, 'unreviewed')


if __name__ == '__main__':
    unittest.main()
