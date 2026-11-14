#!/usr/bin/env python3
"""Offline Codex CLI evidence collection. Exit 0 never proves full isolation."""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import uuid


SOURCE_SHA256 = "4923e8d6d95e5b4b36d541c1abae3444ac1fb79fc990df4b08e0b2881f7b6ba1"
REQUIRED_FLAGS = ("--ephemeral", "--ignore-user-config", "--strict-config",
                  "--skip-git-repo-check", "--sandbox")
REQUIRED_CHECKS = frozenset((
    "version", "capabilities", "baseline", "base_replacement", "developer_append",
    "missing_file", "strict_override", "strict_file", "tool_declarations",
    "project_instructions", "clean_cwd",
))
UNPROVEN = ("end_to_end", "history_isolation", "storage_isolation", "tool_isolation")
VERSION = re.compile(r"codex-cli\s+([0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?)\s*\Z")


class ProbeError(Exception):
    def __init__(self, reason, unsupported=False):
        self.reason = reason
        self.unsupported = unsupported


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def parse_items(stdout):
    """Accept the renderer's JSON array or JSONL; reject missing evidence."""
    try:
        try:
            data = json.loads(stdout)
        except json.JSONDecodeError:
            data = [json.loads(line) for line in stdout.splitlines() if line.strip()]
    except (ValueError, TypeError):
        raise ProbeError("malformed_prompt_evidence") from None
    if not isinstance(data, list) or not data or not all(isinstance(i, dict) for i in data):
        raise ProbeError("missing_prompt_evidence")
    messages = []
    declarations = []
    for item in data:
        if item.get("type") == "message":
            content = item.get("content")
            if not isinstance(content, list) or not content or not all(
                isinstance(block, dict) and isinstance(block.get("text"), str)
                for block in content
            ) or item.get("role") not in ("system", "developer", "user", "assistant"):
                raise ProbeError("malformed_message_evidence")
            messages.append((item["role"], "".join(b["text"] for b in content)))
        elif item.get("type") == "additional_tools":
            tools = item.get("tools")
            if not isinstance(tools, list) or not all(isinstance(t, dict) for t in tools):
                raise ProbeError("malformed_tool_evidence")
            declarations.extend(tools)
    if not any(role == "developer" and text for role, text in messages):
        raise ProbeError("missing_developer_evidence")
    return messages, declarations


def developers(messages):
    return [text for role, text in messages if role == "developer"]


def command_for(executable):
    """Use native executables. Avoid CMD/PowerShell wrapper argument rewriting."""
    if Path(executable).suffix.lower() == ".py" and Path(executable).is_file():
        return [sys.executable, str(Path(executable).resolve())]
    path = shutil.which(executable)
    if not path:
        raise ProbeError("cli_not_found", unsupported=True)
    suffix = Path(path).suffix.lower()
    if suffix == ".py":
        return [sys.executable, path]
    if suffix in (".cmd", ".bat", ".ps1"):
        raise ProbeError("native_cli_required", unsupported=True)
    return [path]


def child_environment(home):
    # Keep OS/loader essentials, never inherited provider/auth/prompt variables.
    allowed = {"PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP",
               "TMPDIR", "LANG", "LC_ALL", "LC_CTYPE", "SYSTEMDRIVE"}
    env = {k: v for k, v in os.environ.items() if k.upper() in allowed}
    env["CODEX_HOME"] = str(home)
    return env


class Probe:
    def __init__(self, command, root, timeout, report):
        self.command = command
        self.root = root
        self.timeout = timeout
        self.report = report
        self.home = root / "home"
        self.cwd = root / "cwd"
        self.home.mkdir()
        self.cwd.mkdir()

    def run(self, args, cwd=None, home=None):
        try:
            result = subprocess.run(
                self.command + args, cwd=cwd or self.cwd,
                env=child_environment(home or self.home), capture_output=True,
                text=True, encoding="utf-8", errors="replace", timeout=self.timeout,
                shell=False,
            )
        except subprocess.TimeoutExpired:
            raise ProbeError("cli_timeout") from None
        except OSError:
            raise ProbeError("cli_start_failed", unsupported=True) from None
        # Neither raw stderr nor prompt text is persisted or printed.
        self.report["invocations"].append({
            "rc": result.returncode,
            "stdout_sha256": digest(result.stdout),
            "stderr_sha256": digest(result.stderr),
        })
        return result.returncode, result.stdout, result.stderr

    def check(self, name, ok, reason, **evidence):
        evidence.setdefault("invocation_index", len(self.report["invocations"]) - 1)
        self.report["checks"].append({"name": name, "status": "pass" if ok else "fail",
                                      "reason": reason, "evidence": evidence})
        if not ok:
            raise ProbeError(reason)

    def render(self, options=(), cwd=None):
        rc, out, _ = self.run(["debug", "prompt-input", *options, "SYNTHETIC-PRECHECK"], cwd=cwd)
        if rc != 0:
            raise ProbeError("offline_renderer_failed")
        return parse_items(out)

    def collect(self, expected):
        rc, out, _ = self.run(["--version"])
        match = VERSION.fullmatch(out.strip())
        observed = match.group(1) if match else None
        self.report["version"] = {"observed": observed, "expected": expected,
                                   "pinned": expected is not None}
        version_ok = rc == 0 and observed is not None and (expected is None or observed == expected)
        self.check("version", version_ok,
                   "version_observed_and_matches_expected" if version_ok else "version_invalid_or_mismatch",
                   observed=observed, expected=expected)

        rc, help_text, _ = self.run(["exec", "--help"])
        flags = {f: bool(re.search(re.escape(f) + r"(?=[\s,=]|$)", help_text))
                 for f in REQUIRED_FLAGS}
        debug_rc, debug_help, _ = self.run(["debug", "prompt-input", "--help"])
        available = rc == 0 and debug_rc == 0 and "prompt-input" in debug_help and all(flags.values())
        self.report["capabilities"] = {"exec_flags": flags, "offline_renderer": available}
        if not available:
            raise ProbeError("required_cli_capability_missing", unsupported=True)
        self.check("capabilities", True, "help_declares_required_capabilities", flags=flags)

        base, tools = self.render()
        baseline_invocation = len(self.report["invocations"]) - 1
        base_devs = developers(base)
        self.check("baseline", bool(base_devs[0]), "baseline_developer_present",
                   developer_sha256=[digest(t) for t in base_devs])

        # Same positive/negative probes as TON-1556, with unique synthetic markers.
        marker = "PRECHECK-BASE-" + uuid.uuid4().hex
        append = "PRECHECK-DEV-" + uuid.uuid4().hex
        instruction = self.root / "instructions.md"
        instruction.write_text(marker + "\n", encoding="utf-8")
        self.report["fixture_sha256"] = {"instructions.md": digest(marker + "\n")}
        replacement, _ = self.render(["-c", "model_instructions_file=" + json.dumps(str(instruction))])
        replace_devs = developers(replacement)
        self.check("base_replacement", replace_devs[0].strip() == marker and
                   replace_devs[1:] == base_devs[1:], "base_slot_replacement_and_residuals",
                   base_slot_replaced=replace_devs[0].strip() == marker,
                   residual_developers_unchanged=replace_devs[1:] == base_devs[1:],
                   residual_developer_sha256=[digest(t) for t in replace_devs[1:]])

        appended, _ = self.render(["-c", "developer_instructions=" + json.dumps(append)])
        appended_devs = developers(appended)
        self.check("developer_append", appended_devs[0] == base_devs[0] and
                   any(append in t for t in appended_devs[1:]), "developer_append_preserves_base",
                   base_unchanged=appended_devs[0] == base_devs[0],
                   append_observed=any(append in t for t in appended_devs[1:]))

        missing = self.root / "missing.md"
        rc, out, err = self.run(["debug", "prompt-input", "-c",
                                "model_instructions_file=" + json.dumps(str(missing)), "SYNTHETIC-PRECHECK"])
        blob = out + err
        self.check("missing_file", rc != 0 and "model instructions file" in blob.lower() and missing.name in blob,
                   "missing_instruction_file_must_be_rejected", rc=rc)

        # A nonexistent provider is a second stop before model access if strict
        # config is broken. Never retry with a real provider or credentials.
        bad_key = "precheck_unknown_" + uuid.uuid4().hex
        strict = ["exec", "--strict-config", "--ignore-user-config", "--ephemeral",
                  "--skip-git-repo-check", "-c",
                  'model_provider="__precheck_nonexistent_provider__"']
        for name, home, overrides in (
            ("strict_override", self.home, ["-c", bad_key + '="synthetic"']),
            ("strict_file", self.root / "bad-home", []),
        ):
            if name == "strict_file":
                home.mkdir()
                (home / "config.toml").write_text(bad_key + '="synthetic"\n', encoding="utf-8")
            # --ignore-user-config intentionally excludes home config; omit it
            # only for the synthetic config.toml negative control.
            args = strict if name == "strict_override" else [a for a in strict if a != "--ignore-user-config"]
            rc, out, err = self.run(args + overrides + ["SYNTHETIC-PRECHECK"], home=home)
            blob = out + err
            self.check(name, rc != 0 and "unknown configuration field" in blob and bad_key in blob,
                       "unknown_key_must_fail_before_provider_or_auth", rc=rc)

        # A declaration is evidence of available tools; zero calls proves nothing
        # about tool exclusion. Do not serialize tool names, descriptions or secrets.
        if any(not isinstance(namespace.get("tools"), list) for namespace in tools):
            raise ProbeError("malformed_tool_evidence")
        declared = [tool for namespace in tools for tool in namespace["tools"]
                    if isinstance(tool, dict) and isinstance(tool.get("name"), str) and tool["name"]]
        self.check("tool_declarations", bool(declared), "nonempty_tool_declarations_required",
                   invocation_index=baseline_invocation,
                   declaration_sha256=digest(json.dumps(tools, sort_keys=True)),
                   declared_count=len(declared))
        self.report["tool_surface"] = {"declarations_observed": True,
                                        "exclusion_proven": False, "tool_calls": 0}

        project = self.root / "project"
        project.mkdir()
        project_marker = "PRECHECK-AGENTS-" + uuid.uuid4().hex
        (project / "AGENTS.md").write_text(project_marker + "\n", encoding="utf-8")
        dirty, _ = self.render(cwd=project)
        dirty_observed = any(project_marker in t for _, t in dirty)
        self.check("project_instructions", dirty_observed,
                   "synthetic_agents_must_be_observed", synthetic_agents_observed=dirty_observed)
        clean, _ = self.render()
        clean_absent = all(project_marker not in t for _, t in clean)
        self.check("clean_cwd", clean_absent,
                   "synthetic_agents_must_be_absent_in_clean_cwd", synthetic_agents_absent=clean_absent)


def new_report():
    return {
        "schema_version": 1, "mode": "offline", "status": "failed",
        "provenance": {"issue": "TON-1556", "source_sha256": SOURCE_SHA256},
        "claims": {"offline_rendering": "not_verified", "full_isolation": "not_proven",
                   **{name: "not_run" if name == "end_to_end" else "not_proven" for name in UNPROVEN}},
        "session": {"fresh_renderer": True, "resume_or_fork": False,
                    "temporary_cwd": True, "temporary_codex_home": True,
                    "credentials_copied": False, "model_calls": 0,
                    "authenticated_execution": "not_run"},
        "checks": [], "invocations": [],
    }


def verify_report(report, expected=None, require_full=False):
    """Structural consistency check, not authentication of an author's evidence."""
    if not isinstance(report, dict) or report.get("schema_version") != 1 or report.get("mode") != "offline":
        return 1, "invalid_report_schema"
    if report.get("status") != "offline_pass":
        return 1, "report_does_not_pass_offline"
    claims = report.get("claims")
    if not isinstance(claims, dict) or claims.get("offline_rendering") != "verified" or \
            claims.get("full_isolation") != "not_proven" or any(
                claims.get(k) != ("not_run" if k == "end_to_end" else "not_proven") for k in UNPROVEN):
        return 1, "missing_or_overstated_claims"
    checks = report.get("checks")
    if not isinstance(checks, list) or not all(isinstance(c, dict) for c in checks):
        return 1, "missing_checks"
    names = [c.get("name") for c in checks]
    if any(not isinstance(n, str) for n in names) or len(set(names)) != len(names) or \
            not REQUIRED_CHECKS.issubset(names) or any(c.get("status") != "pass" for c in checks):
        return 1, "missing_or_failed_checks"
    version = report.get("version")
    if not isinstance(version, dict) or not isinstance(version.get("observed"), str) or \
            not VERSION.fullmatch("codex-cli " + version["observed"]):
        return 1, "missing_version_evidence"
    if (expected is not None and version["observed"] != expected) or \
            (version.get("expected") is not None and version["observed"] != version["expected"]):
        return 1, "version_mismatch"
    capabilities = report.get("capabilities", {})
    if not isinstance(capabilities, dict) or capabilities.get("offline_renderer") is not True or \
            not isinstance(capabilities.get("exec_flags"), dict) or any(
                capabilities["exec_flags"].get(f) is not True for f in REQUIRED_FLAGS):
        return 1, "missing_capability_evidence"
    session = report.get("session")
    if session != new_report()["session"]:
        return 1, "missing_session_evidence"
    surface = report.get("tool_surface")
    if surface != {"declarations_observed": True, "exclusion_proven": False, "tool_calls": 0}:
        return 1, "missing_tool_evidence"
    invocations = report.get("invocations")
    if not isinstance(invocations, list) or not invocations or not all(
        isinstance(i, dict) and type(i.get("rc")) is int and all(
            isinstance(i.get(k), str) and re.fullmatch(r"[a-f0-9]{64}", i[k])
            for k in ("stdout_sha256", "stderr_sha256")) for i in invocations
    ):
        return 1, "missing_invocation_evidence"
    for check in checks:
        evidence = check.get("evidence")
        if not isinstance(evidence, dict) or type(evidence.get("invocation_index")) is not int or \
                not 0 <= evidence["invocation_index"] < len(invocations) or not isinstance(check.get("reason"), str):
            return 1, "missing_check_evidence"
    by_name = {c["name"]: c["evidence"] for c in checks}
    for name, keys in {
        "base_replacement": ("base_slot_replaced", "residual_developers_unchanged"),
        "developer_append": ("base_unchanged", "append_observed"),
        "project_instructions": ("synthetic_agents_observed",),
        "clean_cwd": ("synthetic_agents_absent",),
    }.items():
        if any(by_name[name].get(k) is not True for k in keys):
            return 1, "missing_effective_instruction_evidence"
    for name in ("missing_file", "strict_override", "strict_file"):
        evidence = by_name[name]
        if type(evidence.get("rc")) is not int or evidence["rc"] == 0 or \
                evidence["rc"] != invocations[evidence["invocation_index"]]["rc"]:
            return 1, "missing_rejection_evidence"
    for name, key in (("baseline", "developer_sha256"),
                      ("base_replacement", "residual_developer_sha256")):
        hashes = by_name[name].get(key)
        if not isinstance(hashes, list) or (name == "baseline" and not hashes) or not all(
            isinstance(h, str) and re.fullmatch(r"[a-f0-9]{64}", h) for h in hashes
        ):
            return 1, "missing_instruction_digest_evidence"
    tool_evidence = by_name["tool_declarations"]
    if type(tool_evidence.get("declared_count")) is not int or tool_evidence["declared_count"] <= 0 or \
            not isinstance(tool_evidence.get("declaration_sha256"), str) or \
            not re.fullmatch(r"[a-f0-9]{64}", tool_evidence["declaration_sha256"]):
        return 1, "missing_tool_declaration_evidence"
    if any(by_name["version"].get(k) != version.get(k) for k in ("observed", "expected")) or \
            by_name["capabilities"].get("flags") != capabilities["exec_flags"]:
        return 1, "inconsistent_version_or_capability_evidence"
    fixtures = report.get("fixture_sha256")
    if not isinstance(fixtures, dict) or not isinstance(fixtures.get("instructions.md"), str) or \
            not re.fullmatch(r"[a-f0-9]{64}", fixtures["instructions.md"]):
        return 1, "missing_fixture_evidence"
    if require_full:
        return 1, "full_isolation_not_proven"
    return 0, "offline_rendering_only_full_isolation_not_proven"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", default="codex", help="Native CLI executable (test doubles may be .py)")
    parser.add_argument("--expect-version", help="Exact version, e.g. 0.167.0-alpha.4.1")
    parser.add_argument("--json", type=Path, help="Write a sanitized structured report")
    parser.add_argument("--verify-report", type=Path, help="Validate saved evidence; do not run CLI")
    parser.add_argument("--require-full-isolation", action="store_true", help="Reject offline-only evidence")
    parser.add_argument("--timeout", type=float, default=45, help="Per-child timeout in seconds")
    args = parser.parse_args(argv)
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("--timeout must be positive")
    if args.expect_version is not None and not VERSION.fullmatch("codex-cli " + args.expect_version):
        parser.error("--expect-version must be an exact version token")
    if args.verify_report:
        try:
            report = json.loads(args.verify_report.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            print("FAIL invalid_or_unreadable_report")
            return 1
        rc, reason = verify_report(report, args.expect_version, args.require_full_isolation)
        print(("PASS " if rc == 0 else "FAIL ") + reason)
        return rc

    report = new_report()
    rc = 1
    try:
        command = command_for(args.codex)
        with tempfile.TemporaryDirectory(prefix="cli-precheck-") as directory:
            Probe(command, Path(directory), args.timeout, report).collect(args.expect_version)
        report["status"] = "offline_pass"
        report["claims"]["offline_rendering"] = "verified"
        rc, reason = verify_report(report, args.expect_version, args.require_full_isolation)
        report["reason"] = reason
    except ProbeError as error:
        report["status"] = "unsupported" if error.unsupported else "failed"
        report["reason"] = error.reason
        rc = 2 if error.unsupported else 1
    except (OSError, ValueError, TypeError, KeyError):
        report["reason"] = "local_io_or_evidence_error"
    report["exit_code"] = rc
    if args.json:
        try:
            args.json.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        except OSError:
            print("FAIL report_write_failed")
            return 1
    print(report["status"] + " " + report["reason"])
    print("model_calls=0; end_to_end=not_run; full_isolation=not_proven")
    return rc


if __name__ == "__main__":
    sys.exit(main())
