Independent auth snapshot / commit candidate for TON-1452. Not connected to
LocalCodexBookTransport, CoachIso, real authentication, or packaging.

`CodexAuthSnapshot.create(sourceAuth, runParent, WindowsAuthAtomicCommit(helper))`
allocates a new home containing exactly `auth.json`. Both arguments are explicit;
the module never discovers or reads a default user directory. No configuration,
history, sessions, environment credentials or model settings are copied. The
consumer supplies this home to its isolated child, then stops the whole child
tree before calling `commit()`. Failed/cancelled execution calls `close()` instead.

The snapshot records SHA256 and file identity/timestamps. Commit validates strict
UTF-8 JSON objects, takes a stable sidecar file lock (waiting up to 3 s for another
run's commit, then `WRITER_BUSY`), stages on the source volume, and checks the
source version again. A missing, replaced, damaged or changed
source is preserved. A duplicate completion cannot publish a second time. The
empty `.auth.json.coach-refresh.lock` remains intentionally: unlinking a released
lock file would split the cross-process lock domain. It contains no credentials.
Run-home cleanup never follows links a child made: a directory junction or other
reparse point (which NIO reports as a directory, not a symbolic link), or any
directory whose real path is not its own position under the home, is unlinked
as an entry and its target is left untouched.

An advisory lock plus `check; Files.move(ATOMIC_MOVE)` is insufficient when another
process ignores the lock. `CoachAuthCommit.cs` therefore opens the original as a
Transactional NTFS writer and repeats the expected SHA256 / modification-version
comparison inside the transaction. It replaces the entire contents and length
within that transaction; commit publishes the whole new state atomically. The
original file identity, owner and ACL are preserved. No ACL-setting APIs are used.
Ordinary writes and renames are excluded until commit/rollback, even after the
transacted file handle closes. Readers see the old bytes until commit.

This relies on Windows TxF. Microsoft describes its transactional locking in
[Basic TxF Concepts](https://learn.microsoft.com/en-us/windows/win32/fileio/txf-basic-concepts),
and warns that TxF may not be available in future Windows versions in the
[CreateFileTransactedW reference](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-createfiletransactedw).
Unsupported filesystems/platforms/API failures refuse writeback. The portable
two-argument factory deliberately refuses refreshed writeback rather than using
an unsafe check-and-rename fallback. This candidate needs independent security
review before the backend is selected or packaged for production.

Receipt status is typed and contains no credential bytes, paths or parser input.
`COMMITTED` or `UNCHANGED`, with `cleaned == true`, are the success outcomes.
All other statuses deny success; none can launch Codex or a shell. The native
adapter starts only the explicitly supplied fixed-purpose auth helper, with an
empty environment except SystemRoot and arguments containing paths/hashes/version,
never credential bytes. It suppresses helper output. Timeout, interruption or an
unexpected helper exit returns `OUTCOME_UNKNOWN`: commit may have won just before
the helper stopped. Never blindly retry, report unchanged, or fall back to a raw
process in this case. Snapshot and staging cleanup is still attempted and reported.

Dev compile (to an owned test directory; no install):

```powershell
& "$env:SystemRoot\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:exe /main:CoachAuthCommit /out:<test-dir>\CoachAuthCommit.exe desktopApp/windows-auth/CoachAuthCommit.cs
```

Synthetic regression command on Windows/JDK21:

```sh
tools/dev/heavy --local ./gradlew --no-daemon :desktopApp:test --tests com.coach.desktop.books.CodexAuthSnapshotTest --rerun --console=plain
```

The tests compile a real production entry point and a separate transaction fault
harness into disposable synthetic directories. The production executable accepts
only five positional transaction arguments; fault/barrier flags are absent. Tests
cover no refresh, one refresh, changed/replaced/deleted source, late external
updates after the NIO check, two completion races, another JVM holding the lock
(indefinitely: refusal; for 1 s: waited out and committed), junctions to an
outside synthetic victim left by the child (victim kept, home removed),
invalid JSON/UTF-8, unsupported replacement, rollback after comparison/write/handle
close, preserved ACL/file identity, shorter content, hardlinks, terminal unknown
outcome, auth-only copy and cleanup. A barrier also verifies old-byte reader
isolation plus refusal of uncooperative writes and renames after the final check.
The unguarded-copy negative control actually loses a synthetic concurrent update.
No real account, token, auth file, ACL setting or CLI/model configuration is used.
