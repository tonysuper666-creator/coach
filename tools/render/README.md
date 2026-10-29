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
bounding box of the two renderings. The area rule is only a triage: a small glyph is far below 2% of the box, so
every formula that fails it is reviewed by eye (`review-sheets.py` builds Chromium-over-Painter contact sheets).
`compare.py` also reports an ink-gap diagnostic (an ink pixel of one rendering with no ink within 1px in the other,
missing or extra, in absolute pixels), the ink-mass ratio and the worst 16px-column ink deviation. `selftest.py`
proves the ink-gap diagnostic catches a deleted glyph band and a 3px shift on all 265 real rasters. (The first
version of this diagnostic compared 3x3 min/max ranges and missed deleted thin strokes; do not reintroduce it.) Inline baseline: `baseline.py` reads the Android
`baseline.png` (digits in text vs digits in formulas on one line). Whole-answer replay metrics come from
`check-android.py`, which replays all 20 answers pinned to the bottom and scrolled away from the tail (first-visible
shift counts only samples with content still below the viewport; shorter content is at its end and follows the tail),
and streams the longest answer slowly (8 chars / 700 ms), reads into the middle of a block, changes the window through
the four shapes while the stream is still arriving, then `am kill`s the process mid-stream. After every step the live
anchor (first visible block key, fraction into it, measured from the layout) and the top visible text from
`uiautomator` must be the same; block height is allowed to change. Desktop: `COACH_AUTOPLAY=1|2` with
`-Dcoach.summaryCapturePath=<png>` replays everything in the Mac app, prints the same SUMMARY (frame gaps measured
with a continuous frame loop) and exports the live Skia surface.

Fixes made while taking over: JankStats is created after `setContent`; regex character classes no longer
contain bare `{}` (ICU on Android reads them as string sets and crashed the first paragraph with a formula);
the inline formula baseline is now read from the laid out line (a fixed line height keeps the line box
constant but the baseline moves when the line contains CJK fallback glyphs); Markdown styling matched
`node.type.toString()` (`Markdown:STRONG`) so bold, italic, code and headings never applied.

Reading anchor: `ReplayState` stores `anchorKey`/`anchorFraction`/`pinned`; the screen re-scrolls to the same block at the
same fraction when the width changes and after a process restore (without this the check fails: same block moved from
0.40 to 0.79 of its height on outer -> inner). `autoplay` Intent extras only reset the replay once per `runId`, because
Android re-delivers the task's launch Intent after a kill.

Not verified: IME composition-Enter on a real Mac (Doubao, system Pinyin), `gradlew run` and packaged `.app`
by hand, real Z Fold8 / Pad window dp and physical-device JankStats. The desktop app replays all 20 answers in both modes (see SUMMARY lines); its screenshots come from the in-app Skia surface
export because the agent session may not capture other windows.
No WebView fallback was introduced.
