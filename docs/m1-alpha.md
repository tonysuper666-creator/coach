Coach M1-alpha Android integration

Result: partial. Builder-C's UI heads b2a40a9 and 04e9695 are integrated. The b2 theme/Markdown/composer API is retained; the incoming viewport-reveal logic and all five assertion cases are integrated through the key-based API. The restoration flag is observable so lifecycle waits actually resume. Three knowledge points were repeated in a fresh isolated ledger (runtime captures from b2 before the final focused reveal-lifecycle fix; rendering/theme are unchanged; final phone Enter is restored to newline): three explanations, nine question events, nine actual submissions and nine server grades; Today shows 3/49. After pairing, one tap on Continue starts the cached lesson. The current APK uses the shared ComposerField and AnchoredChatList. Phone Enter inserts a newline and the Send button submits; a new regression checks that no message is sent on Enter.

The UI renders headings, hanging lists, quotes, inline emphasis and math. Titles keep the complete first anchor and expose additional scope in details. Reading is bounded to 600dp, with two panes at 640dp. Status contrast is checked in both themes. Timing stays in developer logs. Reply metadata preserves the actual serving model, including fallback. Subsequent UI polish remains owned by Builder-C.

Current observations

- Cached first word in the latest three journeys: 36 / 65 / 35 ms.
- All seven Builder-R live follow-ups: 5704 / 4221 / 1275 / 6695 / 4514 / 3449 / 5050 ms; 3 of 7 exceed five seconds. No slow sample is removed.
- Latest real Chinese currency question: first word 5050 ms, network off 10.337 seconds, same message resumes with one snapshot and identical client/server UTF-8 SHA-256.
- Ten actual single/two-pane switches preserve the Chinese draft and visible selected radio choice. Reading-anchor behavior also has shared UI tests; the latest manual run does not claim a separately quantified pixel anchor.
- Actual Gboard Chinese Simplified Pinyin, default floating preedit: on-screen n i h a o, then first IME Enter, commits nihao to the draft; followup events remain 1 to 1. This is a real IME run, separate from unit tests.
- JankStats learning/stream/resize: 2455 frames, 53 jank, p95 24.3ms, max 636.5ms. The later scroll segment is 202/1, UI-thread p95 5.2ms, max 73.1ms. gfxinfo measures the full frame separately: 185 frames, 12 janky, p95 21ms. Raw results remain in the evidence; these are different metrics.
- Earlier delivery/review evidence remains historical, with its own APK hashes and phases. The latest screenshot set uses the current b2 UI, both 1248x1972 and 1848x2448, density 420, light/dark. A Pixel Fold emulator exposes multiple physical displays, so the capture tool accepts an explicit SurfaceFlinger display ID instead of silently choosing one.

Call accounting and course coverage

Deduplicating physical call IDs across the formal preparation ledger and the three Builder-R smoke ledgers gives 38 model calls: 31 preparation, five original follow-ups, one f5 follow-up, one b2 follow-up. Copied baselines are not added again. Separate Builder-C upstream acceptance calls are not included in this Builder-R count.

There are five prepared records and 33 VERIFIED questions, stocked 7/6/6/8/6 in the formal service. This is not proof that all five objectives are fully covered: the fourth cached lesson explicitly says its excerpt lacks the named coinsurance/copayment/deductible definitions. First-three learning loops pass; broader source coverage remains incomplete. Formal learning data stays separate from test ledgers. No old learning history is migrated.

Integration and validation

The branch is based on main 11ec183 after #13/#14/#17/#21/#22 merged. It preserves study-companion routes and independent source-index repair. The integration adds prepared lessons, actor-owned supply, compatible schema v5, course ordering, exact-model blind review, persistent service supervision and the Codex subscription-config fix.

Current unique JVM results: domain48, client15, UI33, store17, core15, AI46, app52 = 226; zero failure/error/skip. Unchanged backend/client gates were executed in earlier integration batches; after the UI merge, the following passed again:

    ./gradlew :shared:ui:jvmTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --max-workers=6

The original full shared/server gate and APK build commands are preserved in the issue evidence. An earlier pairing run timed out on hello, and its unchanged retry passed; both logs are retained. The delivery also reruns the full check-pairing.py script against the exact APK, covering fresh-process Keystore, HTTP/WS, allowlist enforcement, dual clients and revocation, with ai_calls=0.

Unchanged bridge code was verified with fault_injection.py (58/58) and exam_protection.py (2/2); those were not needlessly repeated for UI-only changes. There is no daily call cap or utilization pause. Official Claude rejection switches interactive teaching to Codex, replaces the partial prefix and explains the switch. Pool blind reviews still reject a changed vendor/model. November 2 00:00-15:00 UTC rejects model calls before CLI dispatch.

Remaining limits

- Live first word is not consistently within five seconds; streaming/resize also contains jank outliers.
- A real response containing the line below exposes the emphasis markers around the currency amount. This is a new observed case for Builder-C, with the raw image retained; the existing 27 UI tests do not cover it.

    你应该付 **$20**，保险付 $80。

- QA-C's prior snapshot passed visual review without blockers, but suggested more answer space, stronger light selected state, better left-pane/header alignment and less internal wording. These are follow-up UI work, not an approval of every pixel in the new snapshot. Keep the user's complete-title requirement when addressing the left pane.
- Completed chat history is stored server-side but is not yet rehydrated into a fresh Android process. Mastery and question/grade cards restore.
- Physical Fold8 acceptance is still outstanding.
- A formula failure preserves source text. The earlier real source-chip sample now receives HTTP200 from math service; its original cause is not established. Explicit invalid-TeX screenshots use a labelled M0 shared-renderer fault fixture, with HTTP422 confirmed and no model call. They are not invented live tutor output.

Installation

Coach.apk and a plain-language installation guide are copied to the Mac Desktop. Pairing/start/stop launchers control a durable user LaunchAgent release outside task checkouts. Its record and logs live under the user's Coach service/log directories. Pairing produces a fresh six-digit single-use code in the invoking terminal. Both devices need the tailnet; the Mac must be awake, online and logged in. The service data survives stop/start. DeepTutor remains the fallback.
