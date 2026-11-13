# CLI offline precheck

Collect sanitized Codex CLI evidence with Python 3.10+ and no third-party
dependencies. This is an offline renderer/configuration check. **Exit 0 proves
only the offline observations; full isolation remains unproven.** It does not
authorize course evaluation, content acceptance or release.

From the repository root:

```sh
python tools/cli-precheck/precheck.py --expect-version 0.167.0-alpha.4.1 --json offline.json
python tools/cli-precheck/precheck.py --verify-report offline.json --expect-version 0.167.0-alpha.4.1
python tools/cli-precheck/precheck.py --verify-report offline.json --require-full-isolation
python -m unittest discover -s tools/cli-precheck -p 'test_*.py' -v
```

The last report-verification command deliberately exits 1: offline evidence
cannot satisfy full isolation. `--expect-version` compares the entire version
token, including prerelease suffixes. Omitting it records the observed version
as unpinned. Use the version of the exact candidate you intend to inspect; the
example does not prescribe an upgrade.

On Windows, pass the installed **native `codex.exe`** with `--codex <path>` if
PATH resolves to an npm `.cmd`/PowerShell wrapper. Wrappers are rejected as
unsupported to avoid shell quoting ambiguities. On macOS, PATH's native `codex`
works. No package installation or authentication is performed.

The probe uses separate temporary cwd and child-only `CODEX_HOME` for **every**
CLI subprocess, including help/version. It forwards an allowlist of OS/loader
environment variables, excluding inherited provider credentials and prompt
overrides. It never changes global HOME, copies authentication files, reads
course/private evaluation data, invokes resume/fork, or runs an authenticated
model request. The only `exec` calls with a prompt are strict-configuration
negative controls with a deliberately nonexistent provider as a second stop
before model access. They must reject the exact unknown synthetic field; an
authentication/provider error is a failed check, never a pass.

The report records version/capabilities, effective base replacement and
developer append, rejection of missing files/unknown fields (override and
synthetic config file), tool declarations and synthetic AGENTS cwd behavior.
It retains residual developer-block hashes rather than assuming replacing the
base removes other instructions. It uses semantic evidence, not a fixed prompt
length or a fixed number of checks/tools. Raw prompts, stderr, tool descriptions
and host paths are never printed or written; invocation output is represented
by SHA256 only. Temporary synthetic directories are removed on exit.

Exit codes: `0` = offline observations passed; `1` = failed/invalid evidence,
version mismatch, or full isolation requested but unproven; `2` = unavailable
CLI/capability (or argparse usage error). With `--require-full-isolation`, a
successful collection retains `status: offline_pass` and `exit_code: 1`, so
the offline result and the unmet requirement stay distinct. Unsupported or
failed collections still write `--json` when possible. The verifier rejects
missing evidence and overclaims, but does **not** authenticate a report's author
or replace an independent rerun from the exact PR head.

End-to-end nonce execution, authenticated execution, history/storage isolation
and actual tool exclusion are **not verified**. Zero tool calls does not mean
tools are unavailable; observed declarations explicitly leave tool exclusion
unproven. No end-to-end mode is included in this slice.

Provenance: adapted from TON-1556's independently checked synthetic attachment
`codex_isolation_precheck.py`, SHA256
`4923e8d6d95e5b4b36d541c1abae3444ac1fb79fc990df4b08e0b2881f7b6ba1`.
That attachment's Mac offline receipt is not an end-to-end isolation receipt.
Behavior tests run independent fake CLI subprocesses only; they make no model
calls and do not run the installed CLI.
