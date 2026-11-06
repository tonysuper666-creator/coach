import copy
import json
import sqlite3
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from contextlib import closing

from .exporter import (build_notes, build_point_note, effective_attempts, fold_outline, load_snapshot,
                       main, material_sections, mistakes, render_html,
                       render_markdown, write_export, formulas)
from .fixtures import create_database
from .mathml import MathError, mathml


class ExportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = create_database(Path(self.temp.name) / 'synthetic.sqlite')
        self.snapshot = load_snapshot(self.path, 'synthetic')

    def test_historical_wrong_attempts_survive_retry_but_not_void_or_revised_grade(self):
        wrong = mistakes(self.snapshot)
        self.assertEqual([r['attempt_id'] for r in wrong], ['wrong-1', 'shared-wrong', 'legacy-wrong'])
        first = wrong[0]
        self.assertEqual(first['answer'], 'B. 数量上升')
        self.assertEqual(first['correct'], 'A. 数量下降')
        self.assertIn('题库已复核讲解', first['explanation'])
        self.assertIn('UNREVIEWED', wrong[-1]['provenance'])

    def test_letter_submission_uses_frozen_map_instead_of_array_order(self):
        for e in self.snapshot['events']:
            if e['type'] == 'answer_submitted' and e['payload']['attemptId'] == 'wrong-1':
                e['payload']['answer'] = 'B'
        record = mistakes(self.snapshot)[0]
        self.assertEqual(record['answer'], 'B. 数量上升')
        self.assertEqual(record['options'], ['A. 数量下降', 'B. 数量上升'])

    def test_all_eleven_weeks_and_cross_week_counts(self):
        notes = build_notes(self.snapshot)
        self.assertEqual(len(notes), 11)
        self.assertEqual([len(n['mistakes']) for n in notes], [2, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0])
        self.assertIn('历史迁移', notes[0]['mastery']['kp-1'])
        self.assertEqual(notes[1]['mastery']['kp-2'], '已巩固')

    def test_point_note_selects_only_matching_ledger_records(self):
        original = copy.deepcopy(self.snapshot)
        note = build_point_note(self.snapshot, 'shared')
        self.assertEqual([p['id'] for p in note['points']], ['shared'])
        self.assertEqual([r['attempt_id'] for r in note['mistakes']], ['shared-wrong'])
        self.assertEqual(self.snapshot, original)
        with self.assertRaisesRegex(ValueError, '找不到该知识点'):
            build_point_note(self.snapshot, 'missing')

    def test_point_markdown_matches_synthetic_expected_lines(self):
        expected = Path(__file__).with_name('point-example.md').read_text(encoding='utf-8')
        self.assertEqual(render_markdown(build_point_note(self.snapshot, 'kp-2')), expected)
        self.assertIn('本周无错题', render_markdown(build_notes(self.snapshot)[2]))

    def test_independent_sql_matches_every_synthetic_week(self):
        query = Path(__file__).with_name('audit.sql').read_text()
        with closing(sqlite3.connect(self.path)) as db:
            counts = dict(db.execute(query, {'course': 'synthetic'}))
        self.assertEqual(counts, {n['week']: len(n['mistakes']) for n in build_notes(self.snapshot)})

    def test_stale_or_unsupported_projection_never_claims_mastery(self):
        for change in ({'last_seq': 1}, {'version': 999}, {'data': {'version': 4, 'lastSeq': 1}}):
            snap = copy.deepcopy(self.snapshot)
            snap['caches'][0].update(change)
            self.assertIn('未验证', build_notes(snap)[0]['mastery']['kp-1'])
        self.snapshot['caches'][0]['data']['unsupportedTypes'] = ['future-event']
        state = build_notes(self.snapshot)[0]['mastery']['kp-1']
        self.assertIn('缓存记录', state)
        self.assertIn('未验证（future-event）', state)

    def test_candidate_points_are_not_installed_mastery_and_core_does_not_mutate(self):
        original = copy.deepcopy(self.snapshot)
        extra = {'course': {'id': 'synthetic'}, 'knowledgePoints': [{'id': 'new', 'title': '【W8·示例】候选要点'}], 'week7': {'reason': '仅期中复习'}}
        notes = build_notes(self.snapshot, extra)
        self.assertEqual(self.snapshot, original)
        self.assertIn('候选提纲', notes[7]['mastery']['new'])
        self.assertEqual(notes[6]['week_note'], '仅期中复习')
        with self.assertRaises(ValueError):
            build_notes(self.snapshot, {'course': {'id': 'another-course'}})

    def test_outline_move_retire_and_secondary_anchor(self):
        self.snapshot['events'].append({'seq': 500, 'type': 'outline_revised', 'payload': {'ops': [
            {'op': 'move', 'kpId': 'kp-4', 'index': 0}, {'op': 'retire', 'kpId': 'kp-3'},
            {'op': 'rename', 'kpId': 'kp-2', 'title': '【W5·示例】【W6·示例】改名'}]}})
        points = fold_outline(self.snapshot)
        self.assertEqual(points[0]['id'], 'kp-4')
        self.assertNotIn('kp-3', {p['id'] for p in points})
        self.assertEqual(next(p['weeks'] for p in points if p['id'] == 'kp-2'), [5, 6])

    def test_materials_require_explicit_week_headings(self):
        sections = material_sections([('invented.md', '# General\nnot mapped\n## Topic (Week 4–5)\n$y=x$\n### Child\nshared\n## No week\nno mapping')])
        self.assertEqual(sections[4], sections[5])
        self.assertEqual(len(sections[4]), 2)
        self.assertEqual(sections[1], [])
        self.assertNotIn('no mapping', str(sections[4]))

    def test_mathml_is_structured_and_formula_errors_are_visible(self):
        root = ET.fromstring(mathml(r'\frac{\Delta Q}{\Delta P} + \sqrt{x_1^2} \leq 0'))
        ns = {'m': 'http://www.w3.org/1998/Math/MathML'}
        for name in ('mfrac', 'msqrt', 'msubsup'):
            self.assertIsNotNone(root.find('.//m:' + name, ns))
        self.assertIn('<mfrac><mn>1</mn><mn>2</mn></mfrac>', mathml(r'\frac12'))
        exponent = ET.fromstring(mathml('δ^(t-t0)'))
        self.assertEqual(''.join(exponent.find('.//m:msup/m:mrow', ns).itertext()), '(t-t0)')
        for invalid in (r'\unknown{x}', '{x', 'x^^2', '}'):
            with self.assertRaises(MathError): mathml(invalid)
        page, warnings = render_html('$$\n\\unknown{x}\n$$', 'demo')
        self.assertEqual(len(warnings), 1)
        self.assertIn('<merror>', page)

    def test_html_escapes_untrusted_content_and_has_no_network_dependencies(self):
        page, warnings = render_html('# <script>alert(1)</script>\n\n<img src="https://example.invalid/x">\n\n$$\nx^2\n$$', '<private>')
        self.assertFalse(warnings)
        self.assertNotIn('<script>', page)
        self.assertNotIn('<img', page)
        self.assertIn('&lt;script&gt;', page)
        self.assertIn("default-src 'none'", page)
        self.assertIn('@media print', page)
        self.assertIn('<math', page)

    def test_currency_is_not_a_formula_and_plain_equation_clauses_stay_short(self):
        text = '价差 $361→$731→$1,414；公式 $x^2$'
        self.assertEqual(formulas(text), ['x^2'])
        page, warnings = render_html(text, 'money')
        self.assertIn('$361→$731→$1,414', page)
        self.assertEqual(page.count('<math '), 1)
        self.assertFalse(warnings)
        self.assertEqual(formulas('【W1·示例】计算比例 x=2y；第二个 z=3y'), ['x=2y', 'z=3y'])

    def test_unknown_legacy_letter_map_withholds_ambiguous_explanation(self):
        p = next(e['payload'] for e in self.snapshot['events'] if e['payload'].get('attemptId') == 'legacy-wrong')
        p['legacy_explanation_archive']['letter_map_complete'] = False
        record = mistakes(self.snapshot)[-1]
        self.assertIn('暂不导出', record['explanation'])
        self.assertNotIn('B 是错的', record['explanation'])

    def test_export_cli_writes_private_offline_artifacts_without_changing_copy(self):
        before = self.path.read_bytes()
        out = Path(self.temp.name) / 'export'
        self.assertEqual(main(['--database', str(self.path), '--course', 'synthetic', '--out', str(out)]), 0)
        self.assertEqual(len(list(out.glob('week-*.md'))), 11)
        self.assertEqual(len(list(out.glob('week-*.html'))), 11)
        audit = json.loads((out / 'audit.json').read_text())
        self.assertEqual(audit['unique_mistakes'], 3)
        self.assertEqual(audit['formula_warnings'], [])
        self.assertEqual((out / 'week-01.md').stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.path.read_bytes(), before)

    def test_missing_course_and_git_output_fail_closed(self):
        with self.assertRaises(ValueError): load_snapshot(self.path, 'missing')
        root = Path(self.temp.name) / 'repo'
        (root / '.git').mkdir(parents=True)
        with self.assertRaises(ValueError): write_export(build_notes(self.snapshot), root / 'out', self.snapshot)

    def test_revised_legacy_history_stops_instead_of_guessing(self):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('INSERT INTO events(course_id,type,payload) VALUES(?,?,?)', ('synthetic', 'legacy_source_revised', '{}'))
        with self.assertRaisesRegex(ValueError, 'legacy_source_revised'):
            load_snapshot(self.path, 'synthetic')


if __name__ == '__main__':
    unittest.main()
