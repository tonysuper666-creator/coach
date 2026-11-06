import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import validate

FIXTURES = Path(__file__).parent / 'fixtures'


def fixture(name):
    return json.loads((FIXTURES / (name + '.json')).read_text())


class ContentValidationTest(unittest.TestCase):
    def test_four_synthetic_fixtures(self):
        bank = validate.exam_index(FIXTURES / 'exams')
        expectations = {'valid': False, 'missing-field': True, 'bad-index': True, 'overlap': True}
        for name, invalid in expectations.items():
            with self.subTest(name=name):
                scope, errors = validate.check_file(FIXTURES / (name + '.json'), bank)
                self.assertEqual(scope, 'format + overlap')
                self.assertEqual(bool(errors), invalid, errors)

    def test_score_and_selected_items(self):
        data = fixture('valid')
        data['total_marks'] = 10
        self.assertTrue(any('total_marks' in e for e in validate.validate(data)))
        section = data['sections'][0]
        section['choose'] = 1
        self.assertTrue(any('ambiguous' in e for e in validate.validate(data)))
        section['choose'] = 3
        self.assertTrue(any('invalid count' in e for e in validate.validate(data)))

    def test_numeric_tolerance_and_part_marks(self):
        data = fixture('valid')
        part = data['sections'][0]['items'][1]['parts'][0]
        part['answer'] = 'not a number'
        part['tolerance'] = -1
        part['marks'] = 1
        errors = validate.validate(data)
        self.assertTrue(any('numeric answer' in e for e in errors))
        self.assertTrue(any('tolerance' in e for e in errors))
        self.assertTrue(any('do not sum' in e for e in errors))

    def test_types_and_required_fields(self):
        data = fixture('valid')
        data['minutes'] = True
        data['sections'][0]['items'][0]['answer'] = True
        self.assertGreaterEqual(sum('wrong type' in e for e in validate.validate(data)), 2)
        data = copy.deepcopy(data)
        del data['sections'][0]['items'][1]['parts'][0]['rubric']
        self.assertTrue(any('rubric: missing' in e for e in validate.validate(data)))

    def test_exam_directory_must_not_silently_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(ValueError):
                validate.exam_index(Path(temp))
            result = subprocess.run([sys.executable, str(Path(validate.__file__)), str(FIXTURES / 'valid.json'), '--exam-dir', temp], capture_output=True)
            self.assertEqual(result.returncode, 2)

    def test_cli_reports_error_and_does_not_echo_exam_text(self):
        result = subprocess.run([sys.executable, str(Path(validate.__file__)), str(FIXTURES / 'overlap.json'), '--exam-dir', str(FIXTURES / 'exams')], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('eight-word matches', result.stdout)
        self.assertNotIn('violet robot', result.stdout)

    def test_invalid_json_and_unsupported_file_are_not_passes(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'broken.json'
            path.write_text('{', encoding='utf-8')
            self.assertTrue(validate.check_file(path, set())[1])
            path = Path(temp) / 'opaque.bin'
            path.write_bytes(b'123')
            self.assertTrue(validate.check_file(path, set())[0].startswith('not checked'))


if __name__ == '__main__':
    unittest.main()
