"""The rollback contract is selected by the private installed descriptor, never by a passing commit."""
import json
from pathlib import Path
import tempfile
import unittest

from tools.dev.test_release_train import train


HISTORICAL = 'c0c5bb82510694a481351d60733b8c98d6162bb8'
CURRENT = 'cf1f159833a4fd5fadad0b8450b147b4e413f7b2'


class InstalledContractTest(unittest.TestCase):
    def test_historical_v6_contract_is_kept(self):
        self.assertEqual(train.ROLLBACK_CONTRACTS[HISTORICAL], dict(
            inputSchema=6, oldIsInstalled=False, firstMigration=True, reviewOnRollback=False,
            mocksOnRollback=dict(status=404, clientError=True, completed=True)))

    def test_current_installation_is_a_same_schema_rollback_with_review_and_mocks(self):
        self.assertEqual(train.ROLLBACK_CONTRACTS[CURRENT], dict(
            inputSchema=7, oldIsInstalled=True, firstMigration=False, reviewOnRollback=True,
            mocksOnRollback=dict(status=200, clientError=False, completed=True)))

    def test_every_contract_is_the_selected_one_for_its_own_descriptor_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            descriptor = Path(temporary) / 'installation.json'
            for head, contract in train.ROLLBACK_CONTRACTS.items():
                descriptor.write_text(json.dumps(dict(sourceHead=head)))
                self.assertIs(train.installed_rollback(descriptor, head), contract)
                for other in set(train.ROLLBACK_CONTRACTS) - {head}:
                    with self.assertRaisesRegex(RuntimeError, 'differs from the installed'):
                        train.installed_rollback(descriptor, other)

    def test_missing_mismatched_and_unreviewed_installations_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            descriptor = Path(temporary) / 'installation.json'
            with self.assertRaisesRegex(RuntimeError, 'explicit private'):
                train.installed_rollback(None, CURRENT)
            descriptor.write_text(json.dumps(dict(sourceHead=CURRENT)))
            self.assertIs(train.installed_rollback(descriptor, CURRENT), train.ROLLBACK_CONTRACTS[CURRENT])
            with self.assertRaisesRegex(RuntimeError, 'differs from the installed'):
                train.installed_rollback(descriptor, HISTORICAL)
            with self.assertRaisesRegex(RuntimeError, 'differs from the installed'):
                train.installed_rollback(descriptor, '37f89eda20068e2197137b3d3f8aaa814fca5a7d')
            descriptor.write_text(json.dumps(dict(sourceHead='unreviewed')))
            with self.assertRaisesRegex(RuntimeError, 'no reviewed downgrade contract'):
                train.installed_rollback(descriptor, 'unreviewed')


if __name__ == '__main__':
    unittest.main()
