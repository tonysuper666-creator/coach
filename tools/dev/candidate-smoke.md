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

An independently reviewed production copy rehearsal, TON-1175 switch and rollback tools and their independent review, real-request idle window, original phone token, live cutover and rollback remain required before release. This document does not supply executable cutover tools or authorize cutover.

## TON-1175 transaction prototype and isolated fault tests

Run `python3 -m unittest tools.dev.test_ton1175_switch -v` and `git diff --check`; both must exit 0. The tests use temporary files and a fixture controller; their verifier commands deliberately simulate success/failure and are **not** production verifiers. They cover dry runs, prepared release copy, protected recovery material, unchanged old pointers, stale/short/missing idle evidence, verifier rejection, post-proof requests (including one observed while stopping), candidate start failure, failure between pointer replacements, repeated operations, recovery retry and retention of a post-switch database write. They do not establish production-write counts or exercise real launchd, JFR, access-log rotation, credentials or services.

`tools/dev/ton1175_switch.py` takes `prepare|stage|switch|rollback|recover --config CONFIG [--apply]`. Without `--apply` it only reads inputs and invokes the configured **read-only** verifiers; it does not create the state directory, stop/start a service or replace files. With `--apply`, `prepare` copies an already built and dependency-populated payload to a new immutable release path; it does not install dependencies or access the network. `stage` privately saves the current and candidate plist and installation record. `switch` validates evidence, persists a journal, stops the configured owned service, checks the post-window access log again, replaces each pointer atomically, and starts the candidate. Failure triggers a rollback attempt; if that also fails, the retained journal and old files allow `recover --config CONFIG --apply` to be retried after diagnosing the controller. `rollback` and `recover` keep the current database, including writes made after switching. An unknown externally modified pointer is refused for manual investigation. After an interruption, inspect `journal.json` before any new action; `switch` refuses an incomplete transaction.

The private JSON configuration requires absolute `payload`, `release`, `stateDir`, `plist`, `installation`, `idleEvidence`, and `accessLogs` paths; `expectedInstallationSha256`, `expectedSourceHead`, `candidateHead`, `sourceHeadFileSha256`, and `candidateTreeSha256` bind the exact inspected artifacts. Compute the tree digest using `from tools.dev.ton1175_switch import tree_hash` against the prepared payload and the normal SHA-256 over the source-head file and old installation. `controller` is an argument array whose executable accepts `stop` and `start` and controls only the intended service. Preserve and secure this configuration with the retained rollback materials. `source-head.txt` is a claim, not by itself proof of provenance. The copied venv can contain absolute paths and symlinks: relocation and actual dependencies must be verified at the final release path.

Three additional argument arrays are **mandatory trusted external verifiers**, with no production implementations supplied by this change:

- `candidateVerifier RELEASE HEAD`: independently verify clean source/build provenance, executable app, relocated Python environment and all Python/npm dependencies without network or side effects.
- `idleVerifier IDLE_JSON INSTALLATION`: verify the JFR recording covers the entire observed interval, no client socket read occurred, the same live owned service PID and process identity are being observed, and the installation is unchanged. A caller-created JSON assertion alone is not sufficient.
- `logVerifier IDLE_JSON LOG...`: verify complete post-window access-log coverage, including rotation and flushing; called before stopping and again after stopping. The tool additionally rejects any recorded non-hello route after the window.

Each verifier must fail nonzero if it cannot prove its claim. Never configure a command that merely returns success. The fixture verifier is for tests only. **Until real verifiers, their contracts, launchd controller behavior and the complete live cutover/health/automatic-failure procedure are implemented and independently reviewed, this prototype must not be used for production.** In particular, successful `start` is not the five-minute original-token, lecture and exercise acceptance gate; this script cannot enforce or automatically roll back on those later failures. Original mobile token and actual 15-minute idle gates remain unverified. The current task does not authorize production preparation, stop, switch or rollback.
