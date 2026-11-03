# Isolated candidate upgrade smoke

From the candidate checkout, with JDK 17 and already cached Gradle dependencies:

```sh
tools/dev/heavy ./gradlew :server:app:installDist --offline
python3 tools/dev/check-candidate-smoke.py
python3 -m unittest tools.dev.test_first_word_report -v
git diff --check
```

Each command must exit 0. The smoke runs the built distribution on a random loopback port with a separate temporary data, token, log, state and pack directory. A local stub bridge on another random loopback port returns synthetic SSE; no subscription CLI is launched. The process and bridge are stopped and the temporary directory deleted even on failure. The script pairs a synthetic identity, checks rejected credentials and one-use pairing, inspects the stored SHA-256 digest, restarts the server and checks the same credential, then streams a lesson and submits three synthetic choices. It joins the terminal and first-word log on the message and stub request ID, and runs the existing offline report. The sample offsets demonstrate the path, not production performance. Output has no credentials or student data.

This checks restart compatibility within the built candidate, not migration from an identified production release or an existing production token. Schema and actual production configuration must be reviewed independently before authorization to switch.

For the PR 62 merge (`5f954fe`) to PR 63 merge (`7e190c0`), this command reports no changes to the store, migrations, startup or pairing routes:

```sh
git diff --stat 5f954fe 7e190c0 -- server/store/src/main server/app/src/main/kotlin/com/coach/server/Main.kt server/app/src/main/kotlin/com/coach/server/PairingCommand.kt server/app/src/main/kotlin/com/coach/server/LedgerRoutes.kt
```

That source comparison does not identify the installed production revision or prove compatibility with it.

## Production proposal for later independent risk review — do not run during this smoke

The existing `tools/dev/coach-service.py install` writes the fixed production LaunchAgent, copies the distribution and dependencies to a new persistent release, and replaces `installation.json`. Its exception handler restores the previous plist and installation record only if both existed and startup failed. It does not guarantee automatic rollback after successful startup, and pre-start dependency installation may need network; confirm authorization, connectivity and dependencies separately. No action below was performed here.

1. Record and privately retain the exact pre-upgrade `~/Library/LaunchAgents/local.coach.server.plist`, `~/Coach/service/installation.json`, previous release directory, its recorded `sourceHead`, and token-file permissions, without printing token values. Confirm release and binary are intact and that the candidate's database migration is backwards compatible. Obtain an operator-owned rollback location with restrictive permissions.
2. After the independent QA and Architect reviews and the required 15-minute real-request idle window, stop through `python3 "$HOME/Coach/service/control.py" stop`; verify the job and its owned children are stopped. Make a consistent private copy of the stopped `~/Coach/data` directory, including the database, WAL/SHM if present, blobs and token file, and verify its completeness. Preserve logs separately; never delete either release or data. Only a separately authorized operator should execute the existing installer on the fixed, reviewed candidate.
3. After installation, check `control.py status`, authenticate with an existing paired token without exposing it, and verify real lecture and exercise within five minutes. These checks need the actual environment and are not satisfied by the synthetic smoke.
4. If validation fails, stop via `control.py stop`. Restore the retained previous plist and `installation.json` with their original permissions, ensure the recorded release still exists, and run `control.py start`; verify authenticated operation. Only reuse the current database if backward compatibility has been established. If the candidate changed data incompatibly, keep the post-upgrade data for recovery, stop, and consult the owner before restoring the verified pre-upgrade snapshot: snapshot restoration would discard any writes after it. Do not silently overwrite the token or delete records.

The installer may itself restore the old configuration when its `start()` raises, but independently verify the actual running release and credentials; do not assume that branch ran or succeeded. The original-token test, actual upgrade, rollback, 15-minute idle and five-minute post-switch checks remain unverified here.
