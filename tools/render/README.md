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

Environment used for the numbers below: macOS arm64, JDK 17, `ANDROID_HOME` = the Homebrew command-line tools
SDK (it holds `emulator/` and the `sable-api35` image), emulator `-gpu host`, density 420.
`compare.py` and `baseline.py` need Python `numpy`, `pillow`, `playwright` (a venv under `~/Coach/` works).

Comparison rule (Mika, TON-1040): same SVG, 32 px/em, black on white; a pixel differs when any
channel differs by more than 16/255; a formula is consistent when differing pixels are at most 2% of the ink
bounding box of the two renderings. `compare.py` also reports a +-1px-neighbourhood diagnostic, the
ink-mass ratio and the worst 16px-column ink deviation, and writes side-by-side images of every formula
that is not automatically consistent to `review/`. Inline baseline: `baseline.py` reads the Android
`baseline.png` (digits in text vs digits in formulas on one line). Whole-answer replay metrics come from
`check-android.py`, which replays all 20 answers pinned to the bottom and scrolled away from the tail.

Fixes made while taking over: JankStats is created after `setContent`; regex character classes no longer
contain bare `{}` (ICU on Android reads them as string sets and crashed the first paragraph with a formula);
the inline formula baseline is now read from the laid out line (a fixed line height keeps the line box
constant but the baseline moves when the line contains CJK fallback glyphs); Markdown styling matched
`node.type.toString()` (`Markdown:STRONG`) so bold, italic, code and headings never applied.

Not verified: IME composition-Enter on a real Mac (Doubao, system Pinyin), `gradlew run` and packaged `.app`
by hand, real Z Fold8 / Pad window dp and physical-device JankStats. The desktop app starts and replays
(window opened, METAL) but no desktop screenshot was captured: screen capture is not permitted in the agent session.
No WebView fallback was introduced.
