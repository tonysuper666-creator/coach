"""Behaviour tests for tools/dev/first_word_report.py on synthetic log lines.

Every case builds the log it needs, so the expected counts and percentiles are
derived from the injected clock values rather than from a snapshot.
"""
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOL = Path(__file__).with_name("first_word_report.py")
FIXTURE = Path(__file__).with_name("fixtures") / "first_word" / "mixed.log"
sys.path.insert(0, str(TOOL.parent))

import first_word_report as fwr  # noqa: E402


def run_cli(*args):
    return subprocess.run([sys.executable, str(TOOL), *args], capture_output=True, text=True)


def attempt(msg, prepared=None, bridge=None, raw=None, visible=None, end=None,
            parent=None, rid=None, status="complete", queue=None, ttft=None, extra=None):
    """One line shaped exactly like the server's COACH first_word JSON."""
    record = {
        "msg": msg, "rid": rid, "parent_msg": parent,
        "attempt": "fallback" if parent else "primary",
        "prepared_ms": prepared, "bridge_start_ms": bridge, "raw_first_ms": raw,
        "visible_first_ms": visible, "end_ms": end, "status": status,
        "bridge_queue_ms": queue, "bridge_ttft_ms": ttft,
    }
    if extra:
        record.update(extra)
    return "COACH first_word " + json.dumps(record, separators=(",", ":"), ensure_ascii=False)


class ReportCase(unittest.TestCase):
    def report(self, lines, slow_ms=5000.0, limit=20, name="log.txt"):
        with tempfile.TemporaryDirectory(prefix="first-word-") as work:
            path = Path(work) / name
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            return fwr.build_report([path], slow_ms, limit, "unverified")


class StageAttributionTest(ReportCase):
    def test_injected_delays_land_in_the_matching_stage(self):
        report = self.report([attempt("m1", prepared=100, bridge=120, raw=320, visible=820, end=900)])
        stages = report["stages"]["primary"]
        self.assertEqual(stages["prepared_ms"]["p50"], 100)
        self.assertEqual(stages["bridge_start_gap_ms"]["p50"], 20)
        self.assertEqual(stages["bridge_first_raw_ms"]["p50"], 200)
        self.assertEqual(stages["visible_after_raw_ms"]["p50"], 500)
        self.assertEqual(report["visible_first_ms"]["primary"]["p50"], 820)

    def test_same_total_splits_into_different_stages(self):
        slow_filter = attempt("m1", prepared=1, bridge=2, raw=3, visible=603, end=700)
        slow_bridge = attempt("m2", prepared=1, bridge=2, raw=602, visible=603, end=700)
        report = self.report([slow_filter, slow_bridge])
        stages = report["stages"]["primary"]
        self.assertEqual(stages["bridge_first_raw_ms"]["max"], 600)
        self.assertEqual(stages["bridge_first_raw_ms"]["min"], 1)
        self.assertEqual(stages["visible_after_raw_ms"]["max"], 600)
        self.assertEqual(stages["visible_after_raw_ms"]["min"], 1)
        self.assertEqual(report["visible_first_ms"]["primary"]["p50"], 603)

    def test_stage_percentiles_use_their_own_samples_only(self):
        # The slowest attempt has no prepared event, so the prepared sample is
        # smaller than the visible sample: their percentiles are not comparable.
        lines = [
            attempt("m1", prepared=10, bridge=20, raw=30, visible=40, end=50),
            attempt("m2", prepared=None, bridge=None, raw=30, visible=9000, end=9000),
        ]
        report = self.report(lines)
        self.assertEqual(report["stages"]["primary"]["prepared_ms"]["n"], 1)
        self.assertEqual(report["visible_first_ms"]["primary"]["n"], 2)
        self.assertEqual(report["visible_first_ms"]["primary"]["p95"], 9000)

    def test_timestamp_prefix_is_ignored(self):
        line = "2026-11-03T10:00:01.000Z " + attempt("m1", prepared=10, bridge=20, raw=30, visible=40, end=50)
        report = self.report([line])
        self.assertEqual(report["lines"]["first_word"], 1)
        self.assertEqual(report["attempts"]["primary"], 1)
        self.assertEqual(report["stages"]["primary"]["prepared_ms"]["p50"], 10)


class PercentileTest(ReportCase):
    def sample(self, values):
        lines = [attempt("m%d" % index, prepared=0, bridge=0, raw=value, visible=value, end=value)
                 for index, value in enumerate(values)]
        return self.report(lines)["stages"]["primary"]["bridge_first_raw_ms"]

    def test_nearest_rank_documented_rule(self):
        stats = self.sample([50, 10, 30, 40, 20])
        self.assertEqual((stats["n"], stats["p50"], stats["p95"]), (5, 30, 50))

    def test_even_sample_takes_lower_middle(self):
        self.assertEqual(self.sample([40, 10, 30, 20])["p50"], 20)

    def test_two_samples_p95_is_the_maximum(self):
        stats = self.sample([10, 90])
        self.assertEqual((stats["n"], stats["p50"], stats["p95"]), (2, 10, 90))

    def test_empty_stage_stays_null(self):
        report = self.report([attempt("m1", prepared=None, bridge=None, raw=None, visible=None, end=5)])
        for name, stats in report["stages"]["primary"].items():
            self.assertEqual(stats, {"n": 0, "p50": None, "p95": None, "min": None, "max": None}, name)
        self.assertEqual(report["visible_first_ms"]["primary"]["n"], 0)


class DedupTest(ReportCase):
    def test_identical_lines_count_once(self):
        line = attempt("m1", prepared=10, bridge=20, raw=30, visible=40, end=50)
        report = self.report([line, line])
        self.assertEqual(report["lines"]["first_word"], 2)
        self.assertEqual(report["attempts"]["unique_attempts"], 1)
        self.assertEqual(report["duplicates"]["identical_removed"], 1)
        self.assertEqual(report["stages"]["primary"]["prepared_ms"]["n"], 1)

    def test_conflicting_lines_are_excluded_rather_than_chosen(self):
        fast = attempt("m1", prepared=10, bridge=20, raw=30, visible=40, end=50)
        slow = attempt("m1", prepared=10, bridge=20, raw=30, visible=9000, end=9010)
        report = self.report([fast, slow])
        self.assertEqual(report["duplicates"], {"identical_removed": 0, "conflict_keys": 1,
                                                "conflict_attempts_removed": 2})
        self.assertEqual(report["attempts"]["unique_attempts"], 0)
        self.assertEqual(report["visible_first_ms"]["primary"]["n"], 0)
        self.assertEqual(report["excluded_attempts"], 2)
        self.assertEqual(len(report["bad_samples"]["duplicate_conflict"]), 2)


class AttemptSplitTest(ReportCase):
    def test_fallback_is_not_an_independent_command(self):
        lines = [
            attempt("m1", prepared=10, bridge=20, raw=100, visible=9000, end=9100),
            attempt("m2", prepared=None, bridge=110, raw=200, visible=210, end=300, parent="m1"),
        ]
        report = self.report(lines)
        self.assertEqual((report["attempts"]["primary"], report["attempts"]["fallback"]), (1, 1))
        self.assertEqual(report["attempts"]["fallback_parents"], 1)
        self.assertEqual(report["attempts"]["fallback_parent_present"], 1)
        self.assertEqual(report["attempts"]["fallback_parent_missing"], 0)
        # The fallback repeats no preparation and is reported in its own block.
        self.assertEqual(report["stages"]["fallback"]["prepared_ms"]["n"], 0)
        self.assertEqual(report["stages"]["fallback"]["bridge_first_raw_ms"]["p50"], 90)
        self.assertEqual(report["stages"]["primary"]["bridge_first_raw_ms"]["p50"], 80)

    def test_missing_parent_is_surfaced(self):
        report = self.report([attempt("m2", prepared=None, bridge=10, raw=20, visible=30, end=40, parent="gone")])
        self.assertEqual(report["attempts"]["fallback"], 1)
        self.assertEqual(report["attempts"]["fallback_parent_missing"], 1)

    def test_parent_decides_attempt_not_the_label(self):
        line = attempt("m2", prepared=None, bridge=10, raw=20, visible=30, end=40, parent="m1",
                       extra={"attempt": "primary"})
        report = self.report([line])
        self.assertEqual(report["attempts"]["fallback"], 1)
        self.assertEqual(report["attempts"]["primary"], 0)
        self.assertEqual(report["attempts"]["attempt_label_mismatch"], 1)


class NullAndZeroTest(ReportCase):
    def test_zero_durations_are_valid_samples(self):
        report = self.report([attempt("m1", prepared=0, bridge=0, raw=0, visible=0, end=0)])
        stages = report["stages"]["primary"]
        self.assertEqual(stages["prepared_ms"]["p50"], 0)
        self.assertEqual(stages["bridge_first_raw_ms"]["p50"], 0)
        self.assertEqual(report["excluded_attempts"], 0)

    def test_cancelled_and_cache_hit_keep_only_their_events(self):
        lines = [
            attempt("m1", prepared=50, bridge=None, raw=None, visible=None, end=60, status="cancelled"),
            attempt("m2", prepared=None, bridge=None, raw=None, visible=None, end=5),
            attempt("m3", prepared=30, bridge=40, raw=None, visible=None, end=90, status="failed"),
        ]
        report = self.report(lines)
        self.assertEqual(report["attempts"]["status"], {"cancelled": 1, "complete": 1, "failed": 1})
        self.assertEqual(report["stages"]["primary"]["prepared_ms"]["n"], 2)
        self.assertEqual(report["stages"]["primary"]["bridge_start_gap_ms"]["n"], 1)
        self.assertEqual(report["stages"]["primary"]["bridge_first_raw_ms"]["n"], 0)
        self.assertEqual(report["slow_first_word"]["unknown_visible"], 3)
        self.assertEqual(report["excluded_attempts"], 0)


class BadLineTest(ReportCase):
    def test_each_bad_shape_is_counted_and_excluded(self):
        lines = [
            "COACH first_word {\"msg\":",
            "COACH msg.end msg=m1 status=complete",
            "",
            attempt("m1", prepared=-5, bridge=None, raw=None, visible=None, end=10),
            attempt("m2", prepared="100", bridge=None, raw=None, visible=None, end=10),
            "COACH first_word {\"msg\":\"m3\",\"prepared_ms\":NaN,\"status\":\"\"}",
            attempt("m4", prepared=200, bridge=100, raw=None, visible=None, end=300),
            attempt("bad/id with spaces", prepared=10, bridge=None, raw=None, visible=None, end=20),
            attempt("m5", prepared=10, bridge=None, raw=None, visible=None, end=20, status="vanished"),
            "COACH first_word {\"msg\":null,\"status\":\"\"}",
            "COACH first_word [1,2,3]",
        ]
        report = self.report(lines)
        self.assertEqual(report["lines"], {"total": 11, "first_word": 9, "unrelated": 1, "blank": 1})
        self.assertEqual(report["bad"], {
            "json": 1, "missing_key": 1, "negative": 1, "non_finite": 1, "non_number": 1,
            "not_object": 1, "ordering": 1, "out_of_range": 0, "unknown_field": 0,
            "unknown_status": 1, "unsafe_id": 1,
        })
        self.assertEqual(report["attempts"]["unique_attempts"], 0)
        self.assertEqual(report["excluded_attempts"], 9)
        self.assertEqual(report["stages"]["primary"]["prepared_ms"]["n"], 0)

    def test_bad_lines_report_positions_not_content(self):
        lines = [
            "COACH first_word {\"msg\":\"m1\",\"status\":\"\",\"prepared_ms\":-1}",
            "COACH first_word {\"msg\":\"m2\",\"status\":\"\"",
        ]
        report = self.report(lines)
        self.assertEqual(report["bad_samples"]["negative"], [{"file": 0, "line": 1}])
        self.assertEqual(report["bad_samples"]["json"], [{"file": 0, "line": 2}])
        body = json.dumps(report, ensure_ascii=False)
        self.assertNotIn("prepared_ms\":-1", body)

    def test_unknown_field_is_counted_but_the_record_is_kept(self):
        line = attempt("m1", prepared=10, bridge=20, raw=30, visible=40, end=50,
                       extra={"prompt": "SECRET_PROMPT", "url": "https://secret.example/x"})
        report = self.report([line])
        self.assertEqual(report["bad"]["unknown_field"], 1)
        self.assertEqual(report["attempts"]["unique_attempts"], 1)
        body = json.dumps(report, ensure_ascii=False)
        self.assertNotIn("SECRET_PROMPT", body)
        self.assertNotIn("secret.example", body)


class ThresholdTest(ReportCase):
    def test_threshold_is_inclusive(self):
        lines = [attempt("m1", prepared=0, bridge=0, raw=0, visible=5000, end=5000),
                 attempt("m2", prepared=0, bridge=0, raw=0, visible=4999, end=4999)]
        slow = self.report(lines)["slow_first_word"]
        self.assertEqual(slow["count"], 1)
        self.assertEqual(slow["primary"], 1)
        self.assertEqual(slow["details"][0]["msg"], "m1")
        self.assertEqual(slow["threshold_ms"], 5000)

    def test_custom_threshold_selects_other_attempts(self):
        lines = [attempt("m1", prepared=0, bridge=0, raw=0, visible=1500, end=1500)]
        slow = self.report(lines, slow_ms=1000)["slow_first_word"]
        self.assertEqual(slow["count"], 1)
        self.assertEqual(slow["threshold_ms"], 1000)

    def test_details_are_capped_but_counted(self):
        lines = [attempt("m%d" % index, prepared=0, bridge=0, raw=0, visible=6000, end=6000)
                 for index in range(3)]
        slow = self.report(lines, limit=2)["slow_first_word"]
        self.assertEqual(slow["count"], 3)
        self.assertTrue(slow["details_truncated"])
        self.assertEqual(len(slow["details"]), 2)


class CommandLineTest(unittest.TestCase):
    def run_cli(self, *args):
        return run_cli(*args)

    def test_committed_fixture_produces_expected_report(self):
        result = self.run_cli("--log", str(FIXTURE))
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report["lines"], {"total": 23, "first_word": 20, "unrelated": 2, "blank": 1})
        self.assertEqual((report["attempts"]["primary"], report["attempts"]["fallback"]), (8, 1))
        self.assertEqual(report["duplicates"], {"identical_removed": 1, "conflict_keys": 1,
                                                "conflict_attempts_removed": 2})
        self.assertEqual(report["excluded_attempts"], 10)
        self.assertEqual(report["slow_first_word"]["count"], 3)
        self.assertEqual(report["stages"]["primary"]["prepared_ms"]["p50"], 50)
        self.assertEqual(report["stages"]["primary"]["visible_after_raw_ms"]["max"], 5500)
        self.assertFalse(report["input"]["source_verified"])

    def test_reading_does_not_modify_the_input(self):
        before = hashlib.sha256(FIXTURE.read_bytes()).hexdigest()
        self.assertEqual(self.run_cli("--log", str(FIXTURE)).returncode, 0)
        self.assertEqual(hashlib.sha256(FIXTURE.read_bytes()).hexdigest(), before)

    def test_missing_input_exits_two(self):
        result = self.run_cli("--log", str(FIXTURE.with_name("absent.log")))
        self.assertEqual(result.returncode, 2)
        self.assertIn("cannot read input", result.stderr)

    def test_non_finite_threshold_is_rejected(self):
        result = self.run_cli("--log", str(FIXTURE), "--slow-ms", "nan")
        self.assertEqual(result.returncode, 2)


class CliRegressionTest(unittest.TestCase):
    """Real command-line runs for defects found against the first head.

    Each log mixes bad and good lines in one file, which is the case that used to
    abort the whole report: the run must still exit 0 with valid JSON and count
    the rejected lines.
    """

    def setUp(self):
        work = tempfile.TemporaryDirectory(prefix="first-word-cli-")
        self.addCleanup(work.cleanup)
        self.root = Path(work.name)

    def write_log(self, name, *lines):
        path = self.root / name
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return path

    def test_illegal_key_types_are_counted_not_fatal(self):
        log = self.write_log(
            "keys.log",
            attempt("good", prepared=10, bridge=20, raw=30, visible=40, end=50),
            attempt("bad-list", prepared=10, bridge=20, raw=30, visible=40, end=50, rid=[]),
            attempt("bad-object", prepared=10, bridge=20, raw=30, visible=40, end=50,
                    extra={"parent_msg": {"nested": True}}),
        )
        result = run_cli("--log", str(log))
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report["bad"]["unsafe_id"], 2)
        self.assertEqual(report["attempts"]["primary"], 1)
        self.assertEqual(report["excluded_attempts"], 2)
        self.assertEqual(report["bad_samples"]["unsafe_id"],
                         [{"file": 0, "line": 2}, {"file": 0, "line": 3}])

    def test_huge_integer_offset_is_rejected_without_crashing(self):
        huge = "COACH first_word " + json.dumps(
            {"msg": "huge", "status": "complete", "prepared_ms": 10 ** 400})
        log = self.write_log(
            "huge.log",
            attempt("good", prepared=0, bridge=0, raw=1, visible=2, end=3),
            huge,
        )
        result = run_cli("--log", str(log))
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report["bad"]["out_of_range"], 1)
        self.assertEqual(report["attempts"]["primary"], 1)
        self.assertEqual(report["excluded_attempts"], 1)
        self.assertEqual(report["stages"]["primary"]["prepared_ms"]["n"], 1)

    def test_repeated_log_flag_reads_every_file(self):
        first = self.write_log("a.log", attempt("m1", prepared=10, bridge=20, raw=30, visible=40, end=50))
        second = self.write_log("b.log", attempt("m2", prepared=10, bridge=20, raw=30, visible=60, end=70))
        repeated = run_cli("--log", str(first), "--log", str(second))
        listed = run_cli("--log", str(first), str(second))
        self.assertEqual(repeated.returncode, 0, repeated.stderr)
        self.assertEqual(listed.returncode, 0, listed.stderr)
        report = json.loads(repeated.stdout)
        self.assertEqual(report, json.loads(listed.stdout))
        self.assertEqual(len(report["input"]["files"]), 2)
        self.assertEqual(report["attempts"]["primary"], 2)
        self.assertEqual(report["duplicates"]["identical_removed"], 0)


if __name__ == "__main__":
    unittest.main()
