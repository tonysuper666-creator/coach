# Windows book isolation prototype

This directory contains an experimental AppContainer launcher (`CoachIso.cs`). It is not wired into the application and is not yet a production security boundary.

A future Windows book transport must launch the CLI only through a verified container, grant only the per-request work, executable and isolated authentication directories, and pass the excerpt through standard input. The existing `LocalCodexBookTransport` integration is intentionally untouched.

## Helper contract

The helper exits 0 only when the child ran inside the verified container and exited 0. Every other result is a refusal:

- `120` invalid arguments or an image outside the allowed roots (nothing was created).
- `121`–`125` a boundary step failed before the child was resumed; the child was terminated while still suspended, so it executed nothing.
- `126` the child did not finish inside `--timeout-ms`.
- `127` the wait or the exit-code query failed.
- `128` the child may have finished, but the helper could not clean up (ACL revoke, profile delete) or could not discard its output.

Two rules hold for all of them:

1. **The helper truncates its stdout file on every non-zero result** — 120–128, and also a child that exited non-zero on its own (the caller cannot tell those apart from the exit code alone). A non-zero result therefore never leaves a partial answer on stdout, whether the child wrote a full answer, half of one, or nothing. If the file cannot be truncated the helper returns 128 instead.
2. **The helper never touches `answer.json`.** The answer path belongs to the caller (`-o <work>/answer.json` in the production argv), the helper does not know it, so a refused run can leave that file behind. That is why the reader rule below is about the whole work directory, not about the exit code alone.

The helper also sweeps AppContainer profiles left by force-killed runs. If the recorded pid can no longer be queried (for example a protected process has reused the pid, so `HasExited` throws), that profile is treated as alive and skipped; it is never deleted on an unreadable query, and a sweep failure never turns the launch into a refusal.

The child is bound to its Job by `PROC_THREAD_ATTRIBUTE_JOB_LIST` at `CreateProcess` time, so there is no interval in which a created child is outside the Job. The order stays: create suspended, verify the token is an AppContainer with this run's SID, confirm Job membership, then resume.

## Reader contract (required before wiring)

Read `answer.json` and stdout **only** when all three hold:

- the helper exit code is `0`, and
- its stderr contains `COACH_ISO_CHILD_EXIT 0`, and
- its stderr contains no `COACH_ISO_REFUSED`.

Otherwise discard the **entire work directory** for that run, show no answer, and do not fall back to launching the CLI without the container. Checking only the exit code is not enough: `answer.json` survives a refusal by design, and a child can exit with a code that matches a helper refusal.

## Status

A synthetic authenticated CLI call has run in an interactive AppContainer. Safe authentication refresh/writeback, model-initiated denial, cancellation, real-model read attempts and the production transport wiring still require end-to-end verification. Never grant the real book library or the user's full CLI home to the container.
