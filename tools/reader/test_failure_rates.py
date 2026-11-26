import contextlib
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import tempfile
import unittest

from failure_rates import read_counts, report


class ReaderRatesTest(unittest.TestCase):
    def test_cutoff_midnight_non_requests_and_disconnect(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            end = datetime(2026, 11, 26, 7, 30, tzinfo=timezone.utc)
            for day, samples in [(25, [("07:29", "explain", 500), ("07:30", "explain", 200)]),
                                 (26, [("07:00", "explain", 429), ("07:01", "collection/ask", 499),
                                       ("07:02", "collection/ask", 400), ("07:03", "collection/ask", 502),
                                       ("07:30", "collection/ask", 200)])]:
                rows = [dict(at=f"2026-11-{day}T{clock}:00Z", method="POST",
                             route="/v1/library/" + route, status=status) for clock, route, status in samples]
                (root / f"access-2026-11-{day}.log").write_text("\n".join(map(json.dumps, rows)), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()) as result:
                report(read_counts(root, end))
            text = result.getvalue()
            self.assertIn("24h explain 1 1 0 0 50.00% 0.00%", text)
            self.assertIn("24h ask 0 0 1 1 100.00% 50.00%", text)
            self.assertIn("2026-11-25T07:00Z explain 1 0 0 0 0.00% 0.00%", text)

    def test_client_abandon_is_not_an_unexplained_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            end = datetime(2026, 11, 26, 12, 0, tzinfo=timezone.utc)
            rows = [dict(at=f"2026-11-26T11:{minute}:00Z", method="POST",
                         route="/v1/library/explain", status=status)
                    for minute, status in [("20", 200), ("21", 200), ("22", 499), ("23", 499)]]
            (root / "access-2026-11-25.log").write_text("", encoding="utf-8")
            (root / "access-2026-11-26.log").write_text("\n".join(map(json.dumps, rows)), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()) as result:
                report(read_counts(root, end))
            self.assertIn("24h explain 2 0 0 2 0.00% 50.00%", result.getvalue())


if __name__ == "__main__":
    unittest.main()
