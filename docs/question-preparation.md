# Reviewed questions without a second tap

The MCQ supply path retains a failed `MALFORMED` job and atomically queues one new generation job with a different, stricter prompt. The retry lineage survives a restart; a second malformed result in that lineage is terminal. Source-quote repair and independent blind review remain mandatory. No malformed, unsupported or unreviewed question enters the pool. Every physical call, including failed calls and repair calls, remains in `ai_calls`.

An empty pool shows an estimated preparation time, then actual queued/generating/retrying/reviewing states and the number of verified questions. The estimate is not a countdown or promise. A partial review may trigger another batch (at most four batches in an interactive wait); after three minutes the UI reports the timeout explicitly. A full group is pushed automatically, respecting the selected group size. The asynchronous continuation cannot start a teaching turn or create a second unanswered group. While preparing, duplicate taps are disabled; terminal failure re-enables retry.

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

`PoolPreparationSmoke` is an explicit OPPO instrumentation replay against a separate test server, with 20 KP IDs having full pools and no pending groups. Parameters are `coachPort`, `coachDevice`, `coachToken`, and comma-separated `coachKps`. It rejects production/bridge ports, measures on the device from the actual button action until the three-card group's UI is visible (including compositor settling), retains every sample in app-cache `pool-latency.json`, and captures `pool-ready.png`. It does not fabricate learner answers. Use a fresh copy of the prewarmed database so the measurement does not consume production or staging reserves.

The historical 2026-10-31 14:44:09 output was not retained: the bridge disables session persistence and logs metadata rather than model text. Its exact malformed cause therefore remains unverified. Reproduction with the same stored source pack is evidence about current behavior, not recovery of the historical output.
