import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import precheck

HERE = Path(__file__).resolve().parent


def evidence(report, name):
    return next(c["evidence"] for c in report["checks"] if c["name"] == name)


class PrecheckBehaviorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="precheck-tests-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fake = self.root / "fake codex.py"
        shutil.copyfile(HERE / "tests" / "fake_codex.py", self.fake)
        self.log = self.root / "calls.jsonl"
        self.report_path = self.root / "report.json"
        self.caller = self.root / "caller"
        self.caller.mkdir()
        (self.caller / "AGENTS.md").write_text("CALLER-PRIVATE-INSTRUCTIONS", encoding="utf-8")

    def run_probe(self, case="good", *extra):
        self.fake.with_suffix(".json").write_text(json.dumps({"case": case, "log": str(self.log)}), encoding="utf-8")
        env = os.environ.copy()
        env["CODEX_HOME"] = str(self.caller / "private-home")
        env["OPENAI_API_KEY"] = "SYNTHETIC-AUTH-NOT-A-REAL-KEY"
        env["CODEX_DEVELOPER_INSTRUCTIONS"] = "CALLER-PRIVATE-INSTRUCTIONS"
        result = subprocess.run(
            [sys.executable, str(HERE / "precheck.py"), "--codex", str(self.fake),
             "--expect-version", "1.2.3-synthetic", "--json", str(self.report_path), *extra],
            cwd=self.caller, env=env, capture_output=True, text=True, encoding="utf-8", timeout=30,
        )
        report = json.loads(self.report_path.read_text(encoding="utf-8"))
        return result, report

    def calls(self):
        return [json.loads(line) for line in self.log.read_text(encoding="utf-8").splitlines()]

    def verify_saved(self, report, *extra):
        self.report_path.write_text(json.dumps(report), encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(HERE / "precheck.py"), "--verify-report", str(self.report_path), *extra],
            cwd=self.caller, capture_output=True, text=True, encoding="utf-8", timeout=10,
        )

    def test_offline_success_is_limited_and_sanitized(self):
        result, report = self.run_probe()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(report["claims"]["offline_rendering"], "verified")
        self.assertEqual(report["claims"]["full_isolation"], "not_proven")
        self.assertEqual(report["claims"]["end_to_end"], "not_run")
        self.assertFalse(report["tool_surface"]["exclusion_proven"])
        self.assertEqual(report["session"]["model_calls"], 0)
        self.assertEqual(evidence(report, "tool_declarations")["invocation_index"],
                         evidence(report, "baseline")["invocation_index"])
        public = result.stdout + result.stderr + json.dumps(report)
        for secret in ("SYNTHETIC-SECRET-MUST-NOT-APPEAR", "CALLER-PRIVATE-INSTRUCTIONS",
                       "SYNTHETIC-AUTH-NOT-A-REAL-KEY", str(self.root), str(self.root).replace("\\", "\\\\")):
            self.assertNotIn(secret, public)
        calls = self.calls()
        for call in calls:
            self.assertNotIn("OPENAI_API_KEY", call["env_keys"])
            self.assertNotIn("CODEX_DEVELOPER_INSTRUCTIONS", call["env_keys"])
            self.assertNotEqual(call["home"], str(self.caller / "private-home"))
            self.assertNotEqual(call["cwd"], str(self.caller))
            self.assertFalse(call["auth_file_present"])
            self.assertFalse(Path(call["home"]).exists(), "Temporary homes must be cleaned")
            if call["args"][0] == "exec" and "--help" not in call["args"]:
                self.assertIn('model_provider="__precheck_nonexistent_provider__"', call["args"])
        self.assertEqual(self.verify_saved(report).returncode, 0)

    def test_jsonl_renderer_is_supported(self):
        result, _ = self.run_probe("jsonl")
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_version_drift_is_rejected_before_rendering(self):
        result, report = self.run_probe("version_drift")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(report["reason"], "version_invalid_or_mismatch")
        self.assertTrue(all(c["args"] == ["--version"] for c in self.calls()))

    def test_missing_capabilities_are_unsupported(self):
        for case in ("missing_renderer", "missing_strict"):
            with self.subTest(case=case):
                result, report = self.run_probe(case)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(report["status"], "unsupported")
                self.assertEqual(report["claims"]["offline_rendering"], "not_verified")

    def test_missing_cli_still_writes_nonpassing_report(self):
        result, report = self.run_probe("good", "--codex", str(self.root / "missing-cli"))
        self.assertEqual(result.returncode, 2)
        self.assertEqual(report["reason"], "cli_not_found")
        self.assertEqual(report["exit_code"], result.returncode)

    def test_prompt_and_configuration_counterexamples(self):
        cases = {
            "malformed": "malformed_prompt_evidence",
            "empty_evidence": "missing_prompt_evidence",
            "wrong_shape": "missing_prompt_evidence",
            "no_developer": "missing_developer_evidence",
            "malformed_tools": "malformed_tool_evidence",
            "wrong_missing_error": "missing_instruction_file_must_be_rejected",
            "ignored_replacement": "base_slot_replacement_and_residuals",
            "append_instead_of_replace": "base_slot_replacement_and_residuals",
            "lost_residual": "base_slot_replacement_and_residuals",
            "ignored_append": "developer_append_preserves_base",
            "append_replaces_base": "developer_append_preserves_base",
            "ignored_unknown": "unknown_key_must_fail_before_provider_or_auth",
            "auth_error": "unknown_key_must_fail_before_provider_or_auth",
            "provider_error": "unknown_key_must_fail_before_provider_or_auth",
            "ignored_file": "unknown_key_must_fail_before_provider_or_auth",
            "no_tools": "nonempty_tool_declarations_required",
            "ignored_agents": "synthetic_agents_must_be_observed",
            "cwd_leak": "synthetic_agents_must_be_absent_in_clean_cwd",
        }
        for case, reason in cases.items():
            with self.subTest(case=case):
                result, report = self.run_probe(case)
                self.assertEqual(result.returncode, 1, result.stdout)
                self.assertEqual(report["status"], "failed")
                self.assertEqual(report["reason"], reason)
                self.assertNotEqual(report["claims"]["offline_rendering"], "verified")
                self.assertNotIn("SYNTHETIC-SECRET-MUST-NOT-APPEAR", json.dumps(report))

    def test_full_protocol_request_cannot_accept_offline_or_unauthenticated_evidence(self):
        result, report = self.run_probe("good", "--require-full-isolation")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(report["status"], "offline_pass")
        self.assertEqual(report["reason"], "full_isolation_not_proven")
        self.assertEqual(self.verify_saved(report, "--require-full-isolation").returncode, 1)

    def test_timeout_is_a_sanitized_failure_with_report(self):
        result, report = self.run_probe("timeout", "--timeout", "0.2")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(report["reason"], "cli_timeout")
        self.assertNotIn("Traceback", result.stdout + result.stderr)

    def test_report_gaps_and_forged_complete_claims_are_rejected(self):
        _, good = self.run_probe()
        mutations = [
            lambda r: r.pop("session"),
            lambda r: r.pop("tool_surface"),
            lambda r: r.pop("capabilities"),
            lambda r: r.pop("invocations"),
            lambda r: r.pop("version"),
            lambda r: r.pop("fixture_sha256"),
            lambda r: r["checks"][0].pop("evidence"),
            lambda r: evidence(r, "baseline").pop("developer_sha256"),
            lambda r: evidence(r, "base_replacement").update(base_slot_replaced=False),
            lambda r: evidence(r, "missing_file").update(rc=0),
            lambda r: r["checks"].pop(),
            lambda r: r["checks"].append(r["checks"][0]),
            lambda r: r["claims"].pop("history_isolation"),
            lambda r: r["claims"].update(full_isolation="verified"),
            lambda r: r["tool_surface"].update(exclusion_proven=True),
            lambda r: r["version"].update(observed="1.2.30-synthetic"),
        ]
        log_before = self.log.read_bytes()
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                report = copy.deepcopy(good)
                mutate(report)
                result = self.verify_saved(report)
                self.assertEqual(result.returncode, 1, result.stdout)
        self.assertEqual(self.log.read_bytes(), log_before, "Report verification must not invoke CLI")
        mismatch = self.verify_saved(good, "--expect-version", "1.2.30-synthetic")
        self.assertEqual(mismatch.returncode, 1)


if __name__ == "__main__":
    unittest.main()
