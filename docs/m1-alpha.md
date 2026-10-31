Coach M1-alpha Android integration

Result: partial. Three knowledge points completed the learning loop with nine server-graded questions. Cached teaching first word: 35 / 16 / 14 ms. Five real CLI follow-ups: 5704 / 4221 / 1275 / 6695 / 4514 ms; two exceed the 5 second target. These are all observed samples, with no slow sample removed.

The application opens Today, starts the selected knowledge point, streams the actual model's teaching, consumes three independently reviewed questions, grades the selected server letters, and derives mastery from ledger facts. After pairing, one tap on Continue starts teaching. Synthetic answers and test devices live in an isolated acceptance directory; the installed user service retains its own untouched learning ledger.

Integrated inputs include streaming PR #13 through its merge into main, tutor state, verified question pool, Android screens and themes, six-digit device pairing, persistent service supervision, and read-only course loading. The integration adds bounded pool/preparation workers, source-hashed prepared lessons, the prewarm endpoint, a compatible combined schema v5, exact-model blind review, CLI rate-limit fallback, and course order preservation. The bridge has no daily call cap or local utilization pause. Real official Claude rate rejection explicitly switches interactive teaching to Codex and replaces the incomplete prefix. Pool review still rejects an unexpected vendor/model.

Observed evidence

| Check | Observation |
|---|---|
| Three journeys | 3 explanations, 9 question_posed, 9 answer_submitted, 9 attempt_graded; Today 3/49 |
| Prewarm | First five knowledge points: 7 / 6 / 6 / 8 / 6 VERIFIED questions and five prepared lessons; 31 physical CLI calls |
| Call accounting | 31 prewarm calls plus 5 isolated follow-ups = 36 unique physical calls; the smoke DB contains a copied 19-call baseline plus 5 new calls (24), not another 24 calls; cached delivery adds zero |
| Source loading | 49 names/order match, 49 resolved anchors, weights total 100, 49 packs; repeated seed changes no events or source bytes |
| Reconnect | Network off 10.130 seconds; same message resumes with one snapshot revision and identical client/server SHA-256 |
| Window changes | Ten outer/inner size switches retain draft and selected radio answer |
| IME | Real Gboard Simplified Chinese Pinyin: on-screen n i h a o, then IME Enter; draft retained and no follow-up event created |
| JankStats | Journey/stream/resize: 3416 frames, 9 jank, p50 2.4 ms, p95 7.0 ms, p99 27.2 ms, max 422.9 ms |
| Scroll sample | 176 frames, 0 jank, p95 5.0 ms, max 7.3 ms |
| Rendering | Actual formula and literal currency screenshots; 1248×1972 and 1848×2448, light and dark |

Validation executed locally

- JVM results: domain 48, client 15, UI 19, store 17, core 15, AI 46, app 52; 212 total, no failure/error/skip. Tasks were executed in integration batches, with affected modules rerun after changes.
- Final server batch: `./gradlew :server:store:test :server:ai:test :server:app:test :server:app:installDist --max-workers=6`.
- Final Android batch: `./gradlew :shared:ui:jvmTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --max-workers=6`.
- `tools/bridge/.venv/bin/python tools/bridge/tests/fault_injection.py`: 58/58. Includes official 429/rejected terminal, child reaping, unlimited test/user/default traffic, and exact model routing.
- `tools/bridge/.venv/bin/python tools/bridge/tests/exam_protection.py`: 2 tests pass. The authorized 2026-11-02 00:00–15:00 UTC interval blocks every enabled backend before dispatch, physical calls 0.
- `tools/dev/check-pairing.py` supports `COACH_PAIRING_AVD` / `COACH_PAIRING_SERIAL` to give acceptance its own emulator. The full script passed pairing, fresh process/Keystore, HTTP+WS, both cleartext policy tests and revocation, with ai_calls=0. The dual-connection test runs SyncEngine and TutorClient together on two cores, exercising the Android IO dispatcher fix. A separate process test verifies that inherited managed-service settings do not redirect the CLI pairing code away from the terminal or into logs.

Installation and service handoff

The debug APK is copied to the Mac Desktop as Coach.apk. The Mac Desktop also has Coach pairing/start/stop command launchers; the pairing helper starts the service if needed and produces a fresh six-digit, single-use code. Both devices must be connected to the tailnet. The server uses a LaunchAgent and a durable release copy outside task checkouts; its installation record and logs are under the user's Coach service/log directories. Closing the Android app leaves that supervised service available. Stop/start launchers retain its data.

Unfinished acceptance and boundaries

- Real CLI follow-up first word does not consistently meet 5 seconds.
- Physical Fold8 installation, screen shape and keyboard still need device acceptance.
- Completed chat history is stored on the server but is not yet rehydrated into the Android conversation after a fresh process; mastery and question/grade cards do restore.
- Invalid model-generated TeX falls back to a source chip; one live follow-up produced doubled backslashes and did so. Valid fraction/currency examples rendered correctly. This is not a blanket rendering pass for arbitrary generated formulas.
- The raw screenshot sets identify their capture stage. The final Today set includes the system bar contrast fix; the earlier math set uses the same renderer before that status bar adjustment.
- No old learning history migration was performed. DeepTutor remains the fallback. No files or service on port 8790 were changed.
- Rebased onto main 11ec183 after PRs #13, #14, #17, #21 and #22 merged. Main's replay/preflight tests, study-companion routes and independent source-index repair are preserved. The subsequent QA revision limits reading width, puts extra title anchors behind scope details, shows complete first-anchor titles, renders list bullets, retains paragraph boundaries, keeps short in-sentence formulas inline, trims display-math continuation indentation and removes timing metrics from the learner screen. A movable study pane retains draft and choices across the single/two-pane boundary; each question has a stable lazy-list key, so resizing does not move from question two back into question one. The served model propagates to reply metadata. Codex subprocesses use the subscription configuration already allowed by the bridge sandbox, including when launched from a task runtime.

QA handoff, 2026-10-31 03:05 UTC

Items 1–6 of the screenshot review were already implemented and checked before the ownership change: paragraph/list rendering, contextual inline math, complete first-anchor titles, bounded/two-pane reading, stronger unstarted contrast and a separate status row. Timing is retained in logs and hidden from learners. The three knowledge points were repeated in a fresh isolated ledger: nine actual submissions and nine server grades; the refreshed APK restores Today 3/49. Ten actual one/two-pane switches retained a Chinese draft and checked radio choice. Scope details preserve the additional title anchors. Each question owns a stable list key, and message arrival anchors live as long as the controller across a trip back to Today.

Builder-C owns subsequent UI work in TON-1099, including the ComposerField/AnchoredChatList integration. This snapshot is the integration input for that work, rather than the final 07:30 acceptance of the forthcoming UI head. Evidence captures distinguish the original delivery, the reviewed layout and the refreshed package. The original five live latency samples above remain all reported samples from that stage; any further samples belong to the issue's later evidence attachment.
