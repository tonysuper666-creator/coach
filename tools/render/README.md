# Rendering spike (TON-1040, incomplete)

The shared Kotlin delimiter port reads the same synthetic JSON contract as mathsvc.
The common Painter handles MathJax's SVG subset, nested viewBoxes, theme color and
platform CJK text. The replay screen uses a mutable final Markdown AST block,
retains sealed blocks, coalesces deltas at 30 UTF-16 units / 50 ms, and persists
its draft, option, cursor and scroll position outside the repository.

## Reproduce

Use JDK 17 and set `ANDROID_HOME` to the installed Android SDK. Use the heavy lock
for every Gradle invocation and emulator. Run builds before emulator checks.

```sh
npm --prefix tools/mathsvc ci
node --experimental-strip-types tools/render/build-replay.mjs
tools/dev/heavy ./gradlew :shared:domain:jvmTest :shared:ui:jvmTest :androidApp:assembleDebug :desktopApp:createDistributable --max-workers=2
JAVA_TOOL_OPTIONS="-Dcoach.exportFormulas=$HOME/Coach/corpus/render" tools/dev/heavy desktopApp/build/compose/binaries/main/app/Coach.app/Contents/MacOS/Coach
python3 tools/render/compare.py
python3 tools/render/check-android.py
```

The last two commands need Python `playwright`, `pillow`, `numpy`, and an installed
Playwright Chromium. `COACH_CHROMIUM_BIN` can select an existing Chromium executable.
The comparison command must run **after** the foreground Painter exporter exits.
It compares the same SVG at 32 px/em, using every differing RGB pixel as the
strict metric. A channel delta >16 metric is also reported for diagnosis; it does
not replace the strict pass criterion. Inputs, generated assets, images, reports
and video live under `~/Coach/corpus/render/`, never in Git.
`COACH_CORPUS` selects the already staged math input directory;
`COACH_RENDER_CORPUS` selects output for the loader and Android check;
`--corpus` selects input for the comparison.

Desktop reads `COACH_REPLAY_FILE` (default `~/Coach/corpus/render/replay.json`) and
writes state to `COACH_RENDER_STATE` (default `~/Coach/state/render-spike.json`).
Android reads its private `files/replay.json`; the check stages it with `run-as`.
Without a corpus the screen explicitly requests local data rather than presenting
a fabricated tutor response. Exporting formulas is a developer-only entry point.

## Current evidence and handoff

- Shared JVM checks pass: 36 language-independent delimiter cases; replay of
  20 staged answers (215 formulas); 579 currency lines with 0 differences in
  recognized-formula count from the service reference. Tests also cover sealed
  block identity, the Enter policy, state serialization and baseline geometry.
  Formula counts are not a substitute for independently annotating currencies.
- Both debug APK and desktop app-image build, including an offline rebuild.
- Painter raster export completed for 265 formula instances (50 course formulas
  plus all formulas in the 20 answers). Final strict pixel comparison passes only 2/265 (0.75%).
  The Chinese glyph width was corrected to retain the spacing between MathJax's
  1000-unit glyph origins; text ink uses its actual font-size estimate.
- **Android currently crashes on startup**: `JankStats.createAndTrack(window)` is
  called before `setContent`, so `window.peekDecorView()` is null. Initialize it
  after the decor exists, then rerun the complete emulator check. Two Android runs
  encountered this startup problem; work was handed to the configured fallback.
- Four-form state retention, actual background process recovery, JankStats,
  sealed-block displacement, first-visible-message displacement, baseline
  screenshot comparison and the fourteen Android Chinese formulas are **not
  verified**. The height probe reports height changes only; it is not a full
  displacement measurement. The scripts do not establish those acceptance targets.
- Literal code handling, rich table layout, complete cross-platform raster QA,
  formula fallback validation and lifecycle behavior require further review.
- No WebView fallback was introduced. Any route change must follow the issue's
  requirement to report measured Android results before implementing the fallback.
- Li Zheng still needs to test both Doubao and system Pinyin IMEs: multi-character
  composition + Enter, one-letter composition + Enter, Shift+Enter and digit
  candidate selection, in both `gradlew run` and the packaged `.app`. Actual Fold8
  and Pad window dp and physical-device JankStats remain unmeasured.

This is a draft implementation, not an accepted rendering spike. AI physical
calls made by the spike tools: **0**.
