# TON-1193 cross-version data recovery

PR #71 remains an isolated rehearsal adapter until independent acceptance and
architecture review. It does not authorize a production switch.

The old release uses schema V6 and the candidate migrates the same fixed
`HOME/Coach/data` to V7. Before candidate startup, the transaction requires
closed coverage, an absent launchd label, a dead receipt-bound JVM and a free
Darwin database lock. It clones the entire stopped data directory with APFS
`fclonefileat`; unsupported cloning refuses the switch without copying.

The private descriptor records the original and cloned directory identities,
the preflight pin, manifest digest, schema version and ledger high-water mark.
The journal publishes `snapshotted` before either pointer changes. The manifest
includes every regular file and rejects symlinks, hardlinks and special files.
A schema inventory also detects SQLite schema changes outside the data root.

On failed acceptance, candidate shutdown and lock release precede two durable
same-parent renames: `data` to `data.candidate-<nonce>`, then
`data.pre-<nonce>` to `data`. The candidate directory becomes read only. Intent
checkpoints before each rename allow recovery to recognize a crash on either
side of either operation. A phase alone never authorizes a replacement inode:
recovery verifies the bound parent, snapshot identity and manifest, candidate
identity and digest, stopped process receipt, absent job and free locks.
Uncertain layouts or mismatches retain evidence and require manual inspection.

Only a verified `restored` checkpoint changes the preflight's expected data
inode to the bound snapshot inode. All other directory and toolchain checks
remain active. Ordinary byte-identical directory substitutions still fail.

Candidate event delta and mock-session count remain in the quarantine journal.
The digest of the database `pairing_code` rows yields `PAIRING_CHANGED` or
`PAIRING_UNCHANGED`. Recovery always records `NOMERGE`: V7-only writes and
pairing changes are retained for inspection, never applied to V6.

After successful acceptance, `committed` prohibits rollback to V6. Subsequent
faults require forward repair. The V6 snapshot is retained through the next
successful switch and may then be removed only by a deliberate operator action.
Neither snapshots nor quarantine directories are deleted by rehearsal cleanup;
the private fixture remains, while jobs, owned processes, ports and sockets are
cleaned and checked. A new switch requires a new stage.

Validation on macOS/APFS:

```sh
COACH_HEAVY_PRIORITY=release tools/dev/heavy python3 -B -m unittest -v tools.dev.test_ton1193_data_transaction tools.dev.test_ton1193_data_snapshot tools.dev.test_ton1193_snapshots
COACH_HEAVY_PRIORITY=release python3 -B -m tools.dev.check_ton1193_real_acceptance --apply --inject-failure --report ../ton1193-data-rollback.json
COACH_HEAVY_PRIORITY=release python3 -B -m tools.dev.check_ton1193_real_acceptance --apply --report ../ton1193-data-success.json
```

Each real invocation requires a clean committed tree, builds both real releases
and collects at least 900 seconds of same-JVM JFR/activity evidence. The failure
case first performs candidate learning writes, injects acceptance failure, then
checks V6/V7/V6, restored startup high-water, original authenticated token,
lecture, three answers and ledger evidence within a fresh 300-second deadline.
The success case verifies that the retained snapshot digest is unchanged.
