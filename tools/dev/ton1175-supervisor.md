# TON-1175 bounded foreground entry

Run only against an explicitly authorized isolated instance. This change does
not authorize production preparation, stop/start, switch or rollback.

After the existing `prepare` and `stage` steps, use the supervised entry for
`switch`, `rollback` and `recover`:

```sh
python3 tools/dev/ton1175_supervise.py switch --config CONFIG --record-dir NEW_PRIVATE_DIRECTORY
# Same preflight, with explicit transaction side effects in an isolated rehearsal:
python3 tools/dev/ton1175_supervise.py switch --config CONFIG --record-dir ANOTHER_NEW_PRIVATE_DIRECTORY --apply
```

Default is dry-run: service and pointers stay unchanged. Every invocation writes
a new 0700 audit directory with 0600 `events.jsonl` and `owner.json`, and obtains
the staged directory's 0600 `supervisor.lock`. Stage must already exist. A second
supervisor refuses immediately; the transaction's existing lock is also retained.
Waiting for that lock, hashing, preflight, seal acquisition, controller calls and
acceptance all run inside the supervised worker's budget. Configuration and
records must be on a healthy local filesystem; uninterruptible kernel/filesystem
failures cannot be bounded by a userspace process timer.

The transaction budget is 600 seconds. Immediately before candidate startup, a
single checkpoint extends the remaining budget to at least **300 seconds**, so
preparation cannot consume the acceptance reserve. Startup spends that same
300-second reserve. Successful evidence can commit early; this does not promise
continuous observation for five minutes. On timeout or supervisor TERM/HUP/INT,
the worker first receives TERM and gets **150 additional seconds** to run its
existing rollback. If it remains stuck, the supervisor kills and reaps it and
its owned command groups. A separate **180-second** recovery worker is then
allowed for incomplete journals. The maximum scheduled budgets are therefore
600 + 300 + 150 + 180 = 1230 seconds, plus bounded process cleanup overhead
(up to five seconds per reap) and filesystem writes. This is not a 300-second
whole-operation deadline. Recovery has no additional grace period.

The supervisor creates each command session itself. A guardian group leader
waits for a gate; the supervisor saves its PID before releasing execution. The
leader stays alive and unreaped even after its workload exits, pinning the PID
and PGID until cleanup. Every completion, cancellation, timeout or worker exit
kills that exact group and reaps its leader before another command or recovery
can start. It never discovers targets by executable name and never targets a
service or the Multica daemon. The worker inherits only its private RPC socket;
controller/verifier processes inherit neither it nor the coverage socket.

Trusted controller/verifier commands must keep their helper children in their
command session. **A controller that starts a persistent service must put that
service in its own session and close inherited descriptors**, and must control
only its configured instance. It must return success only after the requested
state is established. A service left in the command session will be killed on
command completion. Review this contract before supplying a launchd/controller
adapter; these tests do not validate a production launchd adapter. Commands that
deliberately escape their sessions are outside this ownership contract.

`owner.json` records the worker PID, command PGID, config and journal locations,
budgets, final result and a recover command template. Events record registration,
command exit/timeout, group reap, worker exit code and recovery. They exclude
command argv/output, credentials, user text and free exception strings. These
are live ownership handles, **not permission to kill a recorded PID later**:
after the supervisor exits a PID can be reused. Never replay a stale kill list.

Exit 0 means success/preflight success; 124 means timeout/interruption with no
remaining recovery failure; 1 means a worker failure whose rollback/recovery
succeeded or which occurred before service changes; 2 means supervisor/setup
failure or recovery still required. Consult both the final event and journal.
`switching`, `validating`, `recovering`, `recovery_required` and `switched` are
recoverable. On recovery timeout the journal can remain `recovering`; the final
event explicitly says `recovery_required` and retains the exact recover template.
Fix the controller fault, inspect pointers, then run supervised `recover` with
a **new** record directory and explicit `--apply`. Unknown pointers refuse
automatic recovery. Recovery restores retained pointers and restarts the old
release; it never copies back a database snapshot. New committed rows remain.

The foreground supervisor is the cleanup owner. Keep it alive until it returns.
Worker SIGKILL and stopped workers are handled; SIGKILL of the **supervisor**,
power loss or host failure cannot execute its cleanup. After those failures,
inspect the private journal and actual process identities manually before
recovery. No durable watchdog or crash-proof automatic recovery is claimed.
Do not run an unsupervised transaction concurrently or background this entry.

Observation end to seal is at most **120 seconds**. Background jobs may start in
that gap and be cancelled when the service stops; SQLite transactions protect
committed data. The fence covers application admission and completion, not an
empty maintenance queue or a guarantee that every job finishes before shutdown.

Reproduce without production:

```sh
python3 -m unittest tools.dev.test_ton1175_switch tools.dev.test_ton1175_supervise -v
JAVA_HOME=$(/usr/libexec/java_home -v 17) tools/dev/heavy ./gradlew :server:app:test --tests com.coach.server.ActivityCoverageProtocolTest --offline --console=plain
git diff --check
```

The process tests use short injected budgets (no production CLI override), real
SIGKILL/SIGSTOP/TERM, an ignoring descendant, atomic fixture pointers and SQLite
commits. They test registration races, pre-seal hangs, stopped-stage hangs,
validation hangs, recovery timeout and retry. The JVM protocol tests hold a real
Unix-socket seal, observe HTTP 503, kill/freeze the worker, and require the same
JVM to survive and respond HTTP 200 after EOF releases its seal. Its stop/start
controller remains a fixture; this does not claim a full real service upgrade,
real 900-second JFR, original-device authentication or production learning gates.
