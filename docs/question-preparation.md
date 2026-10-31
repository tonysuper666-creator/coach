# Reviewed questions without a second tap

The MCQ supply path retains a failed `MALFORMED` job and atomically queues one new generation job with a different, stricter prompt. The retry lineage survives a restart; a second malformed result in that lineage is terminal. Source-quote repair and independent blind review remain mandatory. No malformed, unsupported or unreviewed question enters the pool. Every physical call, including failed calls and repair calls, remains in `ai_calls`.

An empty pool shows an estimated preparation time, then actual queued/generating/retrying/reviewing states and the number of verified questions. The estimate is not a countdown or promise. A partial review may trigger another batch (at most four batches in an interactive wait); after three minutes the UI reports the timeout explicitly. A full group is pushed automatically, respecting the selected group size. The asynchronous continuation cannot start a teaching turn or create a second unanswered group. While preparing, duplicate taps are disabled; terminal failure re-enables retry.

The client coalesces the ledger/state/card notifications from one quiz into pending catch-ups instead of blocking the WebSocket reader on repeated HTTP reads. A notification received during an in-flight catch-up still schedules a follow-up; periodic recovery remains enabled. `PoolSyncBurstTest` checks that a burst does not block the socket and that facts committed during the stalled request still reach the replica.
When the outbox is empty, pushed catch-up reads epoch-tagged event pages directly. A changed epoch falls back to full synchronization and snapshot replacement; polling still refreshes backup watermarks and sends pending answers. The restore replay also covers this path with an empty outbox.

## Pool-only prewarm

`POST /v1/prewarm` now accepts `questionsOnly: true`. The authenticated endpoint fills each requested KP to six unseen verified MCQs, without generating a lecture or answering any questions. It runs through the server's actor and existing supply mutex, so operators need not stop the server or open a second SQLite writer. Each KP is bounded to six generation batches, with the single automatic retry per lineage described above. The response reports actual inventory, calls and elapsed time, and does not claim success if fewer than six are available.

Run the same command against an isolated staging instance first, then the authorized production release:

```sh
python3 tools/pool/prewarm.py --url "$COACH_URL" --course "$COURSE" \
  --device "$DEVICE" --token-file "$TOKEN_FILE" \
  --kp-file "$KP_IDS_JSON" --report "$REPORT_JSON"
```

`KP_IDS_JSON` is a private JSON array of KP IDs. The script writes a per-KP report after each response, retains failures, and exits nonzero if any KP misses six. Tokens, original source material and answer keys must not enter Git or a public report. For an offline copy, the existing `PoolCommandKt` / `refillPool` still works; it now recovers interrupted jobs after obtaining `server.lock`.

For TON-1138 production execution: first verify TON-1132 has upgraded production to main, this PR has passed Scout then Architect review and is deployed, and the most recent real request is at least 15 minutes old. Pass `--idle-check /path/to/idle-check` with an executable that returns zero only after verifying the real-request log is idle for 15 minutes; it runs before every KP and stops the command immediately when the check fails. No build, live call or production change during 2026-11-02 00:00–15:00 GMT. Never send test traffic to 8791; use a separately owned bridge and a copied database. An isolated bridge's home must remain under its already-authorized `~/Coach/bridge/work/` sandbox subtree; do not broaden the sandbox to accommodate a misplaced test directory.

## Replays

```sh
tools/dev/heavy ./gradlew :server:ai:test :server:core:test :server:app:test \
  :server:store:test :shared:client:jvmTest --max-workers=2 --console=plain
```

`PoolPreparationFlowTest` replays one click, a malformed generation, the automatic retry, independent review and automatic delivery of exactly three cards. It asserts that zero cards appear before review and that no duplicate group is emitted. `PoolWorkerTest` verifies restart persistence, bounded retry, source repair and failure accounting; client tests check duplicate-tap prevention and terminal recovery.

`PoolPreparationSmoke` is an explicit OPPO instrumentation replay against a separate test server, with 20 KP IDs having full pools and no pending groups. Parameters are `coachPort`, `coachDevice`, `coachToken`, and comma-separated `coachKps`. It rejects production/bridge ports, measures on the device from the enabled button's actual action until the first card's `1 / 3` header is visible (including compositor settling), retains every sample in app-cache `pool-latency.json`, and captures `pool-ready.png`. The submit footer can be below the viewport for long options; it is not the rendering marker. It does not fabricate learner answers. Use a fresh copy of the prewarmed database so the measurement does not consume production or staging reserves. For shared test phones, build both APKs with `-PcoachApplicationId=com.coach.android.ton1138` to isolate test credentials and caches from other installations.

The replay enables opt-in `PoolTrace` checkpoints for card receipt, event read/save, composition, explicit focus and the visible first-card header's draw callback. Set `COACH_POOL_TRACE=1` on the isolated server to log pose/publish durations. These checkpoints contain no question text or credentials; tracing is off by default. Draw callbacks diagnose layout cost, not physical screen presentation, and do not replace the original accessibility + idle + 100 ms measurement or its per-sample 1000 ms assertion. Finish installer/UI automation helpers before instrumentation (they share Android's automation connection). On failure, the replay records foreground package and available traces before teardown, without masking the original error if automation disconnected.

New question groups request their first row before lazy-list measurement, then align its top after the row is ready. Explicit focus cancels an older automatic follow-tail request. The regression test covers rows created inside `BoxWithConstraints`, where a parent effect can run before the child list updates its keys; fixed frame sleeps are not a readiness condition.

If a later restore/tail request replaces the pre-layout request and the row is still absent after two frames, focus falls back to `scrollToItem(index, 0)`. The entire focus operation (row availability, restore and visible-size wait) is bounded to 1500 ms and returns false on timeout, without throwing into composition. This defensive bound does not change the phone's per-sample 1000 ms acceptance limit. Regressions cover an overwritten request and a removed row.

Quiz paragraphs containing only letters, digits and a conservative set of ordinary punctuation render directly with the existing bodyLarge style and full-width layout. This avoids repeated Markdown parsing of plain stems/options during lazy-list measurement. Potential formatting, formulas, entities, URLs, indentation, list markers and punctuation-only lines retain the existing Markdown renderer. The whole question still renders immediately; no content is deferred to move the measurement marker earlier.

The historical 2026-10-31 14:44:09 output was not retained: the bridge disables session persistence and logs metadata rather than model text. Its exact malformed cause therefore remains unverified. Reproduction with the same stored source pack is evidence about current behavior, not recovery of the historical output.

## Import the already reviewed inventory

Prefer importing the staged questions over generating them again in production. `tools/pool/import_reviewed.py` validates source-pack hashes, structural keys, independent reviewer provenance and source quotations, deduplicates existing inventory, then requires every active KP to have six **unseen** reviewed questions. It copies only the four inventory tables, never learner events, devices, messages or jobs. By default it previews against an in-memory database; `--apply --backup NEW_PATH` creates a verified SQLite backup and inserts atomically.

The tool acquires the JVM-compatible POSIX `server.lock` and deliberately refuses a running target server. Do not bypass that lock or write to live production SQLite from a second connection. The reviewed rollout must arrange the authorized service maintenance window and §4.3 full backup first; preserve `dc9dd4d` as the service rollback target. Use an immutable staging snapshot as `--source`, the target data directory as `--target`, the existing source-pack root as `--packs`, and the private course ID as `--course`. Keep the per-KP `--report`; no model calls are made. Rehearse dry-run, apply, idempotent reapply and application reopen on a fresh production copy before any production import.
