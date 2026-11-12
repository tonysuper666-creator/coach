"""Real UTF-8 pipe boundaries, using synthetic data and a hostile local codec."""
import json
from pathlib import Path
import subprocess
import sys
import unittest


class DesktopPipeTests(unittest.TestCase):
    def run_adapter(self, request):
        # -I deliberately ignores global encoding settings. Recreate a non-UTF-8
        # launcher even on macOS, so the adapter must establish its own contract.
        bootstrap = (
            "import sys,runpy; sys.path.insert(0,sys.argv[1]); "
            "[s.reconfigure(encoding='gbk') for s in (sys.stdin,sys.stdout,sys.stderr)]; "
            "runpy.run_module('notes.desktop',run_name='__main__')"
        )
        result = subprocess.run(
            [sys.executable, '-I', '-X', 'utf8=0', '-c', bootstrap, str(Path(__file__).resolve().parents[1])],
            input=json.dumps(request, ensure_ascii=False).encode('utf-8'),
            capture_output=True, timeout=15,
        )
        self.assertEqual(result.stderr, b'')
        return result.returncode, json.loads(result.stdout.decode('utf-8', errors='strict'))

    def test_chinese_and_non_gbk_text_round_trip(self):
        snapshot = {
            'course': '合成课程🧪', 'mistake_policy': 'pending', 'selected_attempt_ids': [],
            'events': [{'seq': 1, 'type': 'outline_revised', 'payload': {'knowledgePoints': [
                {'id': 'synthetic', 'title': '【W1·合成】中文🧪𠮷', 'week': 1},
            ]}}],
            'points': [], 'questions': {}, 'keys': {}, 'pool': {}, 'constructed': {}, 'caches': [],
        }
        code, result = self.run_adapter({'snapshot': snapshot, 'selection': {'week': 1}})
        self.assertEqual(code, 0)
        self.assertIn('中文🧪𠮷', result['markdown'])
        self.assertIn('本周无错题', result['markdown'])
        self.assertNotIn('\ufffd', result['markdown'])

    def test_chinese_error_preserves_failure_exit(self):
        code, result = self.run_adapter({'snapshot': {'course': '合成课程', 'events': []}, 'selection': {'week': 1}})
        self.assertEqual(code, 1)
        self.assertEqual(result, {'error': '课程尚无账本，请先同步'})

    def test_incomplete_snapshot_preserves_safe_error(self):
        code, result = self.run_adapter({})
        self.assertEqual(code, 1)
        self.assertEqual(result, {'error': '导出快照格式不完整'})


if __name__ == '__main__':
    unittest.main()
