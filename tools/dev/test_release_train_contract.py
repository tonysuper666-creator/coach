"""The rollback contract is selected by the private installed descriptor, never by a passing commit."""
import json
from pathlib import Path
import tempfile
import unittest

from tools.dev.test_release_train import train


HISTORICAL = 'c0c5bb82510694a481351d60733b8c98d6162bb8'
CURRENT = 'cf1f159833a4fd5fadad0b8450b147b4e413f7b2'
READER = '5ee14d69faf0f9e6a79a7292a370981263b40d3f'


class InstalledContractTest(unittest.TestCase):
    def test_historical_v6_contract_is_kept(self):
        self.assertEqual(train.ROLLBACK_CONTRACTS[HISTORICAL], dict(
            inputSchema=6, oldIsInstalled=False, firstMigration=True, reviewOnRollback=False,
            mocksOnRollback=dict(status=404, clientError=True, completed=True)))

    def test_current_installation_is_a_same_schema_rollback_with_review_and_mocks(self):
        self.assertEqual(train.ROLLBACK_CONTRACTS[CURRENT], dict(
            inputSchema=7, oldIsInstalled=True, firstMigration=False, reviewOnRollback=True,
            mocksOnRollback=dict(status=200, clientError=False, completed=True)))

    def test_5ee_installation_is_a_same_schema_rollback_that_also_reads_back_reader_data(self):
        self.assertEqual(train.ROLLBACK_CONTRACTS[READER], dict(
            inputSchema=7, oldIsInstalled=True, firstMigration=False, reviewOnRollback=True,
            mocksOnRollback=dict(status=200, clientError=False, completed=True), readerOnRollback=True))

    def test_reader_readback_is_required_only_where_the_installed_build_has_reader_routes(self):
        self.assertEqual([head for head, c in train.ROLLBACK_CONTRACTS.items() if c.get('readerOnRollback')], [READER])
        self.assertEqual(set(train.ROLLBACK_CONTRACTS), {HISTORICAL, CURRENT, READER})

    def test_installed_descriptor_selects_5ee_and_still_allows_returning_to_cf1f(self):
        with tempfile.TemporaryDirectory() as temporary:
            descriptor = Path(temporary) / 'installation.json'
            descriptor.write_text(json.dumps(dict(sourceHead=READER, other='ignored')))
            self.assertIs(train.installed_rollback(descriptor, READER), train.ROLLBACK_CONTRACTS[READER])
            with self.assertRaisesRegex(RuntimeError, 'differs from the installed'):
                train.installed_rollback(descriptor, CURRENT)
            descriptor.write_text(json.dumps(dict(sourceHead=CURRENT)))
            self.assertIs(train.installed_rollback(descriptor, CURRENT), train.ROLLBACK_CONTRACTS[CURRENT])
            with self.assertRaisesRegex(RuntimeError, 'differs from the installed'):
                train.installed_rollback(descriptor, READER)
            descriptor.write_text(json.dumps(dict(sourceHead=READER[:8])))
            with self.assertRaisesRegex(RuntimeError, 'no reviewed downgrade contract'):
                train.installed_rollback(descriptor, READER[:8])

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


class ReaderReadbackTest(unittest.TestCase):
    PAGE = dict(pdfPage=1, text='  Synthetic pears grow in the orchard.\n\f', textSha256='digest-of-page-one')

    def test_anchor_is_an_exact_substring_bound_to_the_served_text_digest(self):
        note = train.anchor_note(self.PAGE, 'version-a')
        self.assertEqual(self.PAGE['text'][note['start']:note['end']], train.READER_QUOTE)
        self.assertEqual(note['quote'], train.READER_QUOTE)
        self.assertEqual((note['textSha256'], note['version'], note['pdfPage'], note['baseRevision'], note['deleted']),
                         ('digest-of-page-one', 'version-a', 1, 0, False))

    def test_page_without_the_quote_fails_closed(self):
        with self.assertRaisesRegex(RuntimeError, 'missing the anchor quote'):
            train.anchor_note(dict(self.PAGE, text='Other text'), 'version-a')

    def test_any_changed_progress_note_revision_or_export_fails(self):
        expected = dict(state=dict(progress=dict(pdfPage=2, revision=7, stale=False),
                                   notes=[dict(id='ton1254-anchor', start=2, end=17, quote=train.READER_QUOTE, revision=8, stale=False)]),
                        markdown='# Synthetic\n')
        summary = train.reader_unchanged(expected, json.loads(json.dumps(expected)), 'rollback')
        self.assertEqual((summary['progressPdfPage'], summary['anchoredNotes'], summary['identical']), (2, 1, True))
        changes = [lambda c: c['state']['progress'].update(pdfPage=1), lambda c: c['state']['notes'][0].update(end=16),
                   lambda c: c['state']['notes'][0].update(revision=9), lambda c: c['state'].update(notes=[]),
                   lambda c: c.update(markdown='# Synthetic\n\nextra\n')]
        for change in changes:
            actual = json.loads(json.dumps(expected))
            change(actual)
            with self.subTest(actual=actual), self.assertRaisesRegex(RuntimeError, 'changed on forward-roll'):
                train.reader_unchanged(expected, actual, 'forward-roll')


if __name__ == '__main__':
    unittest.main()
