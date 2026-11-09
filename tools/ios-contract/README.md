# iOS HTTP/WS contract probe (TON-1412)

Independent Python standard-library client against the actual Ktor executable. It builds
the current checkout, launches a disposable server, makes real authenticated HTTP and
WebSocket requests, restarts that process twice, then removes its data and stops it.
It does not import server implementation classes or call SQLite directly.

## Run

From the repository root, with Python 3.9+ and the repository's JDK/Gradle prerequisites:

```sh
python3 tools/ios-contract/run.py --scope current --report tools/ios-contract/.evidence/current.json
python3 tools/ios-contract/run.py --report tools/ios-contract/.evidence/full.json
python3 tools/ios-contract/run.py --scope current --inject-failure --report tools/ios-contract/.evidence/negative.json
```

Every invocation builds via `tools/dev/heavy ./gradlew :server:app:installDist
--max-workers=2 --console=plain`. There is no attach-to-server option, external URL,
production token input, or stale binary shortcut. Gradle may download dependencies.

| Exit | Meaning |
| --- | --- |
| 0 | Explicit `--scope current`: all supported-contract checks passed. Does **not** accept TON-1412 or the iOS feature. |
| 1 | Build/request/assertion failed. The runner stops at the first failed check; remaining checks were not run. |
| 2 | Default `full`: supported checks passed, but required reader/anchored annotation evidence is missing. |
| 130 | Interrupted; cleanup still runs. |

`--inject-failure` is a deliberate negative control. It sends an invalid token to the
real server and incorrectly expects HTTP 200; the server's 401 must make the process
exit 1 and mark that check failed. It never changes the server or relaxes normal checks.
An unexpected successful response also fails the normal authentication check.

The JSON report includes source HEAD, whether `server/` or `shared/` is dirty, hashes of
contract sources, probe files and all installed server JARs, build exit status, checks,
missing capabilities, unverified surfaces and final exit code. It contains no credentials,
pairing codes, server response bodies, local data paths, or private tailnet addresses.
Expected failures such as 401/409/502 are successful checks only when their specified
recovery/state assertions also pass. Missing capabilities are never counted as passed.
Reports under `.evidence/` are ignored; attach them to the issue for handoff.

## Extracted contracts

Source baseline: main `cf1f159833a4fd5fadad0b8450b147b4e413f7b2`.
The report records the checkout actually built, so a subsequent run is not represented
as this baseline. Relevant sources:

- `docs/adr/ADR-0004-传输与鉴权.md`: Tailscale and per-device bearer token; loopback still authenticates.
- `server/app/src/main/kotlin/com/coach/server/LedgerRoutes.kt`: HTTP auth, pairing, management, commands, offline pack, WS.
- `server/app/src/main/kotlin/com/coach/server/Mainline.kt`: question display, layouts, answer retry/conflicts.
- `server/store/src/main/kotlin/com/coach/store/LedgerStore.kt`: command receipt, journal revision and persistence semantics.
- `server/app/src/main/kotlin/com/coach/server/JournalService.kt`: failed upstream summary returns no invented summary.
- `shared/protocol/src/commonMain/kotlin/com/coach/protocol/LedgerProtocol.kt`: serialized DTOs.
- `shared/client/src/commonMain/kotlin/com/coach/client/HttpLedgerTransport.kt`: existing client behavior, read only.

All paths below require **both** `X-Coach-Device` and `Authorization: Bearer …`, except
exact `POST /v1/pair`. Query-string credentials are rejected even with valid headers.
Kotlin default-valued DTO fields can be omitted; e.g. missing `protocol` means 1 and
missing `hasMore` means false. These are source-defined defaults, not guessed fields.

| Contract | Real request and assertion |
| --- | --- |
| Pair and manage | Admin `POST /v1/pairing-code`; unauthenticated `POST /v1/pair` with `{code,name}` returns `{deviceId,token}`, no-store. Reused code/invalid JSON yields 401. Two separately paired devices read/write the same course. Nonadmin management and admin self-delete yield 403. |
| Hello | `GET /v1/hello`: epoch, sequence and backup watermark; invalid HTTP token and WS handshake yield 401. |
| Immutable outbox | `POST /v1/commands`: `{deviceId,commandId,payloadBytes,payloadSha256}`. `payloadBytes` is the original UTF-8 JSON **string**, SHA-256 over exact bytes. Body `{type,courseId,clientTime,payload}`. `save_note` appends `note_saved`; Unicode survives second-client readback. Same ID/hash replays `DUPLICATE`, preserving event sequences. Changed bytes or invalid hash yield `REJECTED`. Unknown command is HTTP 200 with `REJECTED/UNSUPPORTED_COMMAND`, not a successful mutation. |
| Permanent vs semantic failure | Malformed outer command JSON: 400; header/wire device mismatch: 403. A single journal `CONFLICT` receipt uses HTTP 409. An array uses HTTP 200 even if its receipt is `CONFLICT`; callers must inspect each receipt. |
| Version conflict | `edit_journal` payload `{day,expectedRev,content}`. Concurrent stale revision returns `REV_MISMATCH` with current revision and winning content; loser must not overwrite. `save_note` has **no** version-conflict contract and is **not** a reader annotation. |
| Readback | `GET /v1/courses/{course}/snapshot` plus `/events?after=N&limit=2`; paginated events must equal snapshot events in order without duplicates/gaps. |
| Empty practice | `/mainline` for a course without packs returns `weeks:[]`. `/offline-pack?courseId=…&size=3` returns empty groups/keys, not fabricated questions. Invalid size: 400; absent week: 404. |
| Existing and new question clients | `/mainline/1` keeps pack option order for existing clients. `?layout=shuffled-v1` includes layout. No answer/explanation leaks before submission. `/mainline/1/answer` accepts `{itemId,choice,layout?,submissionId}`. Reused submission for same item replays original choice even if edited; another item conflicts (409). Correct verified answer becomes mastered in the second client's readback. Malformed JSON: 400. |
| WS streaming | `/v1/ws` initially emits an untagged Hello. Client `{t:"hello",epoch,ledgerAfter:{course:seq}}` selects event streaming. Server ledger frames contain `{t:"ledger",course,epoch,events}`. Write from second client appears live. Disconnect, write, reconnect from last cursor replays missing event. Wrong epoch plus large cursor must replay from zero. |
| Existing client WS | Without streaming hello, server sends `t:ledger` with empty events and `httpCatchUp:{after,throughSeq,url}`. Fetch that URL to recover events, matching the existing shared transport. An empty hint is never treated as delivered data. |
| Upstream error | With local bridge deliberately unavailable, `POST /journal/{day}/summary` yields actual 502; manual text is preserved and no summary is fabricated. This tests the summary endpoint; it does not prove every command's possible 5xx-after-commit case. |
| Restart/outage | Stop owned server, observe a real connection failure, restart same temp data. Events, projection, epoch and original command/answer receipts must persist; stale journal still conflicts; WS catches up. |
| Revocation | Admin `DELETE /v1/devices/{id}` yields 204. Existing socket closes with 1008; subsequent HTTP read/write and new WS handshake yield 401. Revocation persists after another restart; second client remains authorized. |

## Isolation and limits

The runner uses only a randomly selected loopback port, excluding 18791/8791/8790/3790.
The server gets a temporary Java `user.home`, data, tokens, packs, generated papers,
logs, state and activity socket. Inherited app/bridge/JVM injection is cleared;
`COACH_TAIL_HOST` is empty, managed children are disabled, source lookup is disabled,
and the only bridge URL is `http://127.0.0.1:1`. No AI CLI or device account is called.
Fixture questions are invented orchard examples. Credentials remain in memory/temp
files; server logs remain private temporary files and are deleted on exit. Cleanup
terminates/waits for the exact child process owned by the runner.

The two independent wire clients model existing HTTP/WS and new streaming behavior;
they do not execute Android UI or Swift code. Physical iPhone/Tailscale connectivity,
Swift codecs and UI recovery require TON-1411's separate evidence. Phone Tailscale
installation is confirmed by the owner; this loopback probe does not measure it.

## Required next stage — not yet accepted

At the baseline, main contains no reader endpoints. PR118 reader progress is a candidate
and PR117 is also unmerged; neither was tested here. Anchored annotations are still a
server dependency under TON-1400. Default full validation therefore intentionally exits
2; no route guessed from a proposal is counted as supported.

After the reader/annotation implementation lands on main, extend this directory's
adapter and source inventory from the **merged** code, create synthetic reading content,
then verify read position, anchored highlight/comment mutation, revision conflict,
second-client readback, process restart and reconnect with real requests. Only remove
the missing capability when those assertions actually run. Rerun all three commands
above and attach fresh reports identifying the merged source HEAD. Do not change the
default to current, convert missing to skip/pass, or claim full validation from current's
exit 0. Candidate-head evidence, if later collected, must remain separately labeled.
