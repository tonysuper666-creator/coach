Coach M1-alpha Android integration

Result: partial. Three knowledge points completed the learning loop with nine server-graded questions. Cached teaching first word: 35 / 16 / 14 ms. Five real CLI follow-ups: 5704 / 4221 / 1275 / 6695 / 4514 ms; two exceed the 5 second target. These are all observed samples, with no slow sample removed.

The application opens Today, starts the selected knowledge point, streams the actual model's teaching, consumes three independently reviewed questions, grades the selected server letters, and derives mastery from ledger facts. After pairing, one tap on Continue starts teaching. Synthetic answers and test devices live in an isolated acceptance directory; the installed user service retains its own untouched learning ledger.

Integrated inputs include streaming PR #13 through a40ffced, tutor state, verified question pool, Android screens and themes, six-digit device pairing, persistent service supervision, and read-only course loading. The integration adds bounded pool/preparation workers, source-hashed prepared lessons, the prewarm endpoint, a compatible combined schema v5, exact-model blind review, CLI rate-limit fallback, and course order preservation. The bridge has no daily call cap or local utilization pause. Real official Claude rate rejection explicitly switches interactive teaching to Codex and replaces the incomplete prefix. Pool review still rejects an unexpected vendor/model.

Observed evidence

| Check | Observation |
|---|---|
| Three journeys | 3 explanations, 9 question_posed, 9 answer_submitted, 9 attempt_graded; Today 3/49 |
| Prewarm | First three knowledge points: 7 / 6 / 6 VERIFIED questions and three prepared lessons; 19 physical CLI calls |
| Call accounting | 19 prewarm calls plus 5 isolated follow-ups = 24 physical calls; cached delivery adds zero |
| Source loading | 49 names/order match, 49 resolved anchors, weights total 100, 49 packs; repeated seed changes no events or source bytes |
| Reconnect | Network off 10.130 seconds; same message resumes with one snapshot revision and identical client/server SHA-256 |
| Window changes | Ten outer/inner size switches retain draft and selected radio answer |
| IME | Real Gboard Simplified Chinese Pinyin: on-screen n i h a o, then IME Enter; draft retained and no follow-up event created |
| JankStats | Journey/stream/resize: 3416 frames, 9 jank, p50 2.4 ms, p95 7.0 ms, p99 27.2 ms, max 422.9 ms |
| Scroll sample | 176 frames, 0 jank, p95 5.0 ms, max 7.3 ms |
| Rendering | Actual formula and literal currency screenshots; 1248×1972 and 1848×2448, light and dark |

Validation executed locally

- JVM results: domain 44, client 12, UI 16, store 13, core 15, AI 46, app 35; 181 total, no failure/error/skip. Tasks were executed in integration batches, with affected modules rerun after changes.
- Final server batch: `./gradlew :server:store:test :server:ai:test :server:app:test :server:app:installDist --max-workers=6`.
- Final Android batch: `./gradlew :shared:ui:jvmTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --max-workers=6`.
- `tools/bridge/.venv/bin/python tools/bridge/tests/fault_injection.py`: 58/58. Includes official 429/rejected terminal, child reaping, unlimited test/user/default traffic, and exact model routing.
- `tools/bridge/.venv/bin/python tools/bridge/tests/exam_protection.py`: 2 tests pass. The authorized 2026-11-02 00:00–15:00 UTC interval blocks every enabled backend before dispatch, physical calls 0.
- `tools/dev/check-pairing.py` supports `COACH_PAIRING_AVD` / `COACH_PAIRING_SERIAL` to give acceptance its own emulator. The dual-connection test runs SyncEngine and TutorClient together on two cores, exercising the Android IO dispatcher fix.

Installation and service handoff

The debug APK is copied to the Mac Desktop as Coach.apk. The Mac Desktop also has Coach pairing/start/stop command launchers; the pairing helper starts the service if needed and produces a fresh six-digit, single-use code. Both devices must be connected to the tailnet. The server uses a LaunchAgent and a durable release copy outside task checkouts; its installation record and logs are under the user's Coach service/log directories. Closing the Android app leaves that supervised service available. Stop/start launchers retain its data.

Unfinished acceptance and boundaries

- Real CLI follow-up first word does not consistently meet 5 seconds.
- Physical Fold8 installation, screen shape and keyboard still need device acceptance.
- Completed chat history is stored on the server but is not yet rehydrated into the Android conversation after a fresh process; mastery and question/grade cards do restore.
- Invalid model-generated TeX falls back to a source chip; one live follow-up produced doubled backslashes and did so. Valid fraction/currency examples rendered correctly. This is not a blanket rendering pass for arbitrary generated formulas.
- The raw screenshot sets identify their capture stage. The final Today set includes the system bar contrast fix; the earlier math set uses the same renderer before that status bar adjustment.
- No old learning history migration was performed. DeepTutor remains the fallback. No files or service on port 8790 were changed.
- This integration remains based on PR #13's unmerged work. Rebase onto main after #13 merges; do not merge this PR first.
