Coach serial resource queues (TON-1299)

Use `COACH_HEAVY_PRIORITY=release tools/dev/heavy COMMAND ...` or
`tools/dev/heavy --priority release COMMAND ...` for the release line.
`--emulator` accepts the same priority option. Lane 1 remains the emulator
lane; build lanes remain 2..N+1, controlled by the existing lanes file.
Release waiters go first within each pool; otherwise live waiters keep arrival
order. Tickets live beside the existing locks in `.coach-heavy.lock.waiters/`;
exited waiters are ignored and removed. Existing holders and stale-lock recovery
are unchanged. Running copies of the old script do not participate in priority
scheduling; allow them to finish normally. Every waiting/new invocation needs the
updated script for the priority guarantee to cover the whole queue.

The local testphone scripts are delivered separately as the TON-1299 attachment
`testphone-ton1299.tar.gz`, because `~/Coach/testphone` is outside this repository.
Use `wait.sh add TON-N --priority release`; `list` shows actual scheduling order
and defaults legacy entries to normal. Re-registration keeps the original
arrival timestamp and priority unless explicitly overridden. Each fresh lease,
including direct `lease.sh acquire`, enters `lease-history.json`; reacquire by
its current holder and renewals do not add attempts. Watch imports successful
historical `已触发` records once before its existing log rotation. Manual grants
predating this ledger cannot be reconstructed; imported counts are a lower bound.

At 5 or more grants, automatic dispatch requires a reliably read issue status
of `done` or `in_review`. Other states stay queued and receive the root-cause
comment at most once per 24 hours. Unreadable status/history fails closed. The
existing spin detection still applies. Active leases remain untouched. Direct
acquire records attempts but is not the automatic dispatch gate.

Verification, without Gradle, devices, or real locks:

```sh
python3 tools/dev/check-heavy.py
python3 tools/dev/check-resource-queues.py --testphone-dir /path/to/extracted/testphone
```

The second command copies scripts into an isolated temporary directory and
provides fake multica, ADB, and probe executables. It exercises release-first
ordering, FIFO including arrivals within one second on macOS Bash 3, cancellation, the 5-grant gate, comment
deduplication, accepted/unknown status, log rotation, renewals, add/list, and
preservation of a renewed active lease (TON-1146), and a live legacy emulator
holder that must not block current build lanes (TON-1241/1253). Without the option it copies the installed
`~/Coach/testphone` scripts. No command talks to a real device or the platform.

For local script updates, save `.bak-<timestamp>` copies in `~/Coach/testphone`
first, install `queue.py` before its callers, and replace files with atomic
rename. Do not unload the LaunchAgent or alter a live lease. To roll back,
atomically restore the three shell backups; retain the new ledger for audit.
