"""Numbers-only checks for the persona retest table. No window, no books."""
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "smoke"))
import persona_retest_table as table


class RateTests(unittest.TestCase):
    def test_rounding_matches_version_rates(self):
        self.assertEqual(table.rate({"ok": 1, "timeout": 1}), "50.0% (1/2)")
        self.assertEqual(table.rate({"ok": 2, "refusal": 1}), "33.3% (1/3)")
        self.assertEqual(table.rate({}), "n/a")
        self.assertEqual(table.rate({"ok": 0, "other": 0}), "n/a")

    def test_top_reasons_keep_closed_order_on_ties(self):
        text = table.top_reasons({"timeout": 1, "source": 1}, {"quota": 2})
        self.assertEqual(text, "额度 2，超时 1，出处 1")

    def test_first_run_has_no_previous_column(self):
        run = {
            "version": "2.0.1928",
            "commit": "abc",
            "personas": [{
                "id": "P1",
                "name": "备考",
                "opened": True,
                "steps_done": 2,
                "steps_total": 4,
                "stuck": "模考",
                "explain": {"ok": 0},
                "ask": {"ok": 1, "timeout": 1},
                "crash": 0,
            }],
            "checks": {"countdown": {"status": "通过", "stuck": "—"}},
        }
        text = table.render(run, None)
        self.assertIn("上一版 无（本跑即基线）", text)
        self.assertIn("| 备考 | 已配置 | 2/4 | 模考 | n/a | 50.0% (1/2) | 0/1 | — | — | — | — |", text)
        self.assertIn("| 首页倒计时 | 通过 | — | — |", text)
        self.assertNotIn("http", text)
        self.assertNotIn("配对", text)

    def test_previous_run_sits_beside_this_one(self):
        run = {
            "version": "2.0.1928",
            "personas": [{
                "id": "P2", "name": "朋友", "opened": True,
                "steps_done": 3, "steps_total": 3, "stuck": "—",
                "explain": {"ok": 1}, "ask": {"ok": 1}, "crash": 0,
            }],
            "checks": {},
        }
        baseline = {
            "version": "2.0.5",
            "personas": [{
                "id": "P2", "name": "朋友", "opened": True,
                "steps_done": 1, "steps_total": 3, "stuck": "提问",
                "explain": {"ok": 0, "refusal": 1}, "ask": {}, "crash": 1,
            }],
            "checks": {"cite": {"status": "未测"}},
        }
        text = table.render(run, baseline)
        self.assertIn("上一版 2.0.5", text)
        self.assertIn("| 朋友 | 已配置 | 3/3 | — | 0.0% (0/1) | 0.0% (0/1) | 0/1 | 1/3 | 100.0% (1/1) | n/a | 1/1 |", text)
        self.assertIn("| 对比表出处高亮 | 未测 | 未测 | — |", text)


class ExamDateTests(unittest.TestCase):
    def test_shifts_only_the_date(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "library.sqlite"
            connection = sqlite3.connect(path)
            connection.execute(
                "CREATE TABLE collections (id TEXT, kind TEXT, title TEXT, exam_date TEXT)"
            )
            connection.execute(
                "INSERT INTO collections VALUES ('c1','COURSE','secret title','2026-12-15')"
            )
            connection.commit()
            connection.close()
            moved = table.set_exam_offset(path, 14, today=__import__("datetime").date(2026, 11, 27))
            self.assertEqual(moved["before"], "2026-12-15")
            self.assertEqual(moved["after"], "2026-12-11")
            connection = sqlite3.connect(path)
            title, exam = connection.execute("SELECT title, exam_date FROM collections").fetchone()
            connection.close()
            self.assertEqual(title, "secret title")
            self.assertEqual(exam, "2026-12-11")
            self.assertIsNone(json.loads(json.dumps(moved)).get("title"))

    def test_no_course_date(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "library.sqlite"
            connection = sqlite3.connect(path)
            connection.execute("CREATE TABLE collections (id TEXT, kind TEXT, title TEXT, exam_date TEXT)")
            connection.execute("INSERT INTO collections VALUES ('b1','BOOK','x', NULL)")
            connection.commit()
            connection.close()
            self.assertIsNone(table.set_exam_offset(path, 14))

    def test_fills_blank_course_date(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "library.sqlite"
            connection = sqlite3.connect(path)
            connection.execute("CREATE TABLE collections (id TEXT, kind TEXT, title TEXT, exam_date TEXT)")
            connection.execute("INSERT INTO collections VALUES ('c1','COURSE','secret title','')")
            connection.execute("INSERT INTO collections VALUES ('b1','BOOK','x', NULL)")
            connection.commit()
            connection.close()
            moved = table.set_exam_offset(path, 14, today=__import__("datetime").date(2026, 11, 27))
            self.assertEqual(moved["before"], "")
            self.assertEqual(moved["after"], "2026-12-11")
            connection = sqlite3.connect(path)
            rows = dict(connection.execute("SELECT id, exam_date FROM collections").fetchall())
            connection.close()
            self.assertEqual(rows["c1"], "2026-12-11")
            self.assertIsNone(rows["b1"])

    def test_clear_open_session_drops_reader_restore(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "library.sqlite"
            connection = sqlite3.connect(path)
            connection.execute("CREATE TABLE book_open_session (slot INTEGER PRIMARY KEY, book TEXT, screen TEXT)")
            connection.execute("INSERT INTO book_open_session VALUES (1, 'secret-id', 'READER')")
            connection.commit()
            connection.close()
            self.assertEqual(table.clear_open_session(path), 1)
            connection = sqlite3.connect(path)
            left = connection.execute("SELECT book, screen FROM book_open_session").fetchall()
            connection.close()
            self.assertEqual(left, [])
            self.assertEqual(table.clear_open_session(path), 0)

    def test_clear_open_session_missing_table(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "library.sqlite"
            connection = sqlite3.connect(path)
            connection.execute("CREATE TABLE collections (id TEXT)")
            connection.commit()
            connection.close()
            self.assertEqual(table.clear_open_session(path), 0)


if __name__ == "__main__":
    unittest.main()
