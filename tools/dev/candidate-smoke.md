# Candidate verification and production upgrade gates

## Synthetic, isolated smoke

On the candidate checkout with JDK 17 and cached Gradle dependencies:

```sh
tools/dev/heavy ./gradlew :server:app:installDist --offline
python3 tools/dev/check-candidate-smoke.py
python3 -m unittest tools.dev.test_first_word_report -v
git diff --check
```

Each command must exit 0. The smoke uses loopback ports, temporary data and a synthetic bridge; it cleans up its processes and files. It tests synthetic pairing, restart authentication, lecture, three answers and first-word/report correlation. Its timings are not production performance. It does not test an upgrade of the production database or an original token.

## Actual production baseline and compatibility

Architect independently compared the installed `5ef99895d9fad277bfbdbb97b64eda6a551242e9` to candidate `6e5a704`: no schema migration or pairing/device-token implementation changes. Verify the current installed head and installation-record hash again before staging and switching; this comparison is invalid if production moved. Projection cache version changes from 4 to 6: either version replays on a version mismatch. One KP now has at most one bound thread; three existing `qa:` threads must be opened using their original thread IDs, never desktop `VerifyHooks`'s generated `kp:<id>` for those KPs. The new projector understands `legacy_source_revised` and lets an outline `add` revive a retired KP, whereas the old projector does not preserve those semantics on rollback.

## Required isolated upgrade rehearsal

The synthetic smoke above is not this gate. With a consistent SQLite backup copied read-only from production into a private temporary directory, run the candidate on random loopback ports with isolated data, logs, state, packs and a controlled local bridge. Verify the original desktop token against the copy at `/v1/hello` (200), open all three existing `qa:` KPs with their existing thread IDs, and exercise lecture and three answers on the copy. Suppress copied background jobs, do not call a real tutor, never print or export credentials or private learning content, and clean up owned processes and copies. Record candidate head, checks and `productionWrites=0` as reviewable evidence. An isolated copy and synthetic bridge do not prove the live mobile token or production cutover.

On the machine holding the production data, with the reviewed candidate built as above, run `python3 tools/dev/check-production-copy-upgrade.py`. The program refuses a changed production source head or changed candidate build sources. It prints only aggregate results and removes its private copy; do not redirect its output into a public place without review. Its three questions and lecture are synthetic and run on the copy, not against the three historical threads.

## Cutover plan — not yet approved for execution

Use a reviewed, tested TON-1175-specific version of the `~/Coach/service/upgrade-ton1139-main-5ef9989/` staging, idle observation, switch and rollback pattern. Do not reuse its hard-coded old heads, production cache import or cache restoration. Independent QA and cross-review must cover the new tools and evidence before execution.

1. Before the outage, stage immutable `ton1175-main-6e5a704`, including its built app, Python venv and npm dependencies. Prepare restrictive-permission copies of the old and new plist and `installation.json`; record the exact old installation record SHA-256, installed source head and old release path. Ensure the previous release remains runnable. Do not run `install()` in the outage: it installs dependencies, names releases by timestamp and only restores pointers on certain startup failures.
2. Before cutover, verify the isolated upgrade evidence and candidate head, review the intended release, check the retained rollback files, and make a consistent private pre-switch snapshot with its integrity result. Freeze incremental `migrate-deeptutor` revisions and re-adding retired KPs until the rollback window closes.
3. Prove at least 15 minutes of real-request inactivity, then immediately recheck the installation record's SHA-256 and source head. Abort if any non-`/v1/hello` route appears after the idle proof, if evidence is stale, or if any other gate fails. Stop only the owned service and atomically replace the plist and installation record with the staged versions; start the candidate. No production test traffic or real tutor call is authorized by the isolated smoke.
4. Within five minutes, verify `/v1/hello` with the original desktop token, and verify the original phone token by the first actual phone request's HTTP 200 in the access log without printing credentials. Verify real lecture and exercise under the separately authorized production gate. If the phone is offline or any required proof is missing, production acceptance remains blocked; a synthetic token cannot substitute. On a failed cutover or failed required validation immediately roll back.
5. Rollback stops the owned candidate, atomically restores the retained old plist and `installation.json`, starts the old release and verifies its health and original authentication. **Keep the current database and its post-switch writes**: there is no schema change. Never automatically restore the pre-switch database snapshot. Only if `integrity_check` fails may the owner decide whether to use the snapshot, with explicit recognition that it loses later real writes.

An independently reviewed production copy rehearsal, TON-1175 switch and rollback tools and their independent review, real-request idle window, original phone token, live cutover and rollback remain required before release. The developer tools below remain blocked from production and do not authorize cutover.

## TON-1175 transaction and evidence validators

See [ton1175-validation.md](ton1175-validation.md) for commands, private evidence schemas, the five-minute acceptance transaction, recovery and reproducible isolated faults. `ton1175_switch.py` now keeps startup and acceptance in one foreground transaction. The candidate and JFR validators inspect real artifacts; the current access-log sink cannot establish lossless coverage, so its validator deliberately refuses every negative-coverage claim. Production remains blocked on that service-side contract and independent review. No production preparation, stop, switch, rollback, phone operation or real tutor call is authorized by these tests.
