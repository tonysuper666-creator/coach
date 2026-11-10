# Reader page cache measurements

Baseline production source: `1014caf0f57d89bf7b937e8eaa8cd1236675c677`.
At that revision, `PdfPageImage` requests and decodes on every page change.
The original AWT baseline is preserved at PR141 head `5f7062c`. The measured
Poppler baseline is `27f07f8` plus the four named `waitUntil` argument fixes from
the Mac evidence package. Current code implements the three-page decoded window;
its native candidate measurements and memory/presentation acceptance are pending.
PR131's isolated Reader2 layout and PR134's desktop window remain separate work.

## Mac desktop baseline

The repository's `AGENTS.md` keeps shared UI tests and their compilation on Mac.
Run this in a clean checkout of the measurement commit before adding the cache:

```sh
COACH_READER_MEASUREMENT_PHASE=baseline tools/dev/heavy --local ./gradlew \
  :shared:ui:jvmTest --tests com.coach.ui.ReaderPageBaselineTest --rerun-tasks
```

Keep the same test source, JVM, machine, window and synthetic material when
repeating with `COACH_READER_MEASUREMENT_PHASE=candidate`. Archive each run's
`shared/ui/build/reader-measurements/<phase>/`, test XML and exact source SHA
before running another Gradle command. The phase is read in the test JVM;
`--rerun-tasks` prevents an up-to-date result from replacing a measurement.

The native Compose test window exercises `PrivateSourceReference`,
`PrivatePdfDocument`, `ReaderHttp`, and the platform decoder. It sends pointer
input, records 20 forward and 20 reverse turns, and reopens a citation. Each
sample checks a distinct background in the captured target page, so a changed
semantic tag with stale image pixels does not count as display. HTTP is an
isolated synthetic fixture, not the production server or real textbook.

`inputToVerifiedCaptureUpperBoundMs` includes input injection, test
synchronization and screenshot readback. Its p50/p95/max are **upper bounds**,
not first-present timing. Completed HTTP responses do not prove decoding or
prefetch completion. Both are deliberately reported as unobserved, as is a
decoded cache hit. Keep exact input-to-presentation measurements separately;
these samples alone cannot establish the issue's 100 ms acceptance criterion.

Heap use and 1200×1600×4 byte estimates are context only. They do not establish
native bitmap lifetime, process peak RSS, GPU retention, or the three-image
budget. Do not infer those from the map size or heap deltas. Capture live
decoded images, in-flight decodes and renderer references during fast turns,
book/configuration/version changes, and after closing the reader.

## Pixel Fold and real service path

Generate a separate synthetic library for the existing isolated app/server
acceptance setup on Mac:

```sh
python3 tools/reader-cache/synthetic_library.py \
  --output /path/to/new-isolated-library --course READER_BASELINE
```

The generator refuses an existing directory. Its PDF and catalog contain only
invented labels. Point only the disposable candidate service at it; do not
replace the user's library, pairings, or installed application configuration.
Use `tools/dev/heavy --emulator` for the existing Pixel Fold setup. Record the
actual AVD, SDK, window, density, fontScale, render parameters, material hash,
source SHA and app build. Use the same settings for baseline and candidate.

The native desktop fixture above is not Android evidence. Android measurement
automation and first-present/live-image instrumentation still need to be
connected before a complete baseline can be claimed. Record at least 20
sequential inputs plus citation jumps, separate completed-prefetch hits from
misses, retain individual times and p50/p95/max, and capture `dumpsys meminfo`
before, during the peak, and after closing. Keep desktop RSS and decoded-image
lifetime evidence alongside it. Real material, if measured locally, must never
appear in Git, logs, screenshots or attachments.

## Supplemental observed baseline

`ReaderPageTrace` is off by default. The new native hosts enable it before
composition and enter the existing reader dialog, HTTP client and platform
decoder. No application navigation, build file, manifest, account, or saved
pairing changes are needed. Android uses the existing ui-test-manifest Activity
and requires the isolated package `com.coach.readermeasurement`.

Run on Mac, from a clean checkout, with the same settings for the later cache
candidate:

```sh
tools/dev/heavy --local python3 tools/reader-cache/run_observed.py \
  --platform desktop --output build/reader-observed/desktop-baseline

tools/dev/heavy ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest \
  -PcoachApplicationId=com.coach.readermeasurement

# Launch the Pixel Fold AVD using the existing emulator resource protocol first.
# HOST is the existing coach.tailHost IPv4; no network policy/configuration change.
tools/dev/heavy --emulator python3 tools/reader-cache/run_observed.py \
  --platform android --output build/reader-observed/android-baseline \
  --host HOST --serial emulator-5580 --perfetto \
  --aapt2 "$ANDROID_HOME/build-tools/37.0.0/aapt2" \
  --apk androidApp/build/outputs/apk/debug/androidApp-debug.apk \
  --test-apk androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
```

The runner owns and joins the fixture service, test command, sampler and
optional 30-second Perfetto capture before returning. It retains failure logs.
It rejects physical devices and other application IDs before installing either
APK. The fixture renders only its newly generated PDF with Poppler at 144 dpi,
serves 24 PNGs at 1200×1600, and refuses production ports. This is synthetic HTTP
fixture evidence; it is not a full candidate service/ReaderLibrary acceptance.

The original JVM fixture used an AWT-encoded synthetic page. The supplemental
hosts both use Poppler pages. Keep the two baselines separate; compare the
future candidate with the corresponding unchanged supplemental fixture.

Each side records 20 forward and 20 reverse pointer inputs, two citation opens,
and reader-close/GC-request checkpoints. `trace.json` records the production
click handler, lookup miss, request/decode readiness, actual target bitmap draw
commands, and pixel-verified capture endpoint. Stale image draws from another
page or document session cannot establish the new target's draw. Android also
records frame submission where hardware rendering supports the callback.

Android's [frame commit callback](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable))
describes swap-chain submission and does not guarantee visibility. Desktop has
no presentation callback here. `summary.json` therefore separates handler to
draw, submission and pixel-observation upper bound, and leaves first physical
presentation unverified. For Android, align the fixed `ReaderPage/...` markers
with the app frame and its corresponding SurfaceFlinger display frame in
`frames.perfetto-trace`; the app timeline's end is insufficient. See the
[FrameTimeline documentation](https://perfetto.dev/docs/data-sources/frametimeline).
Dropped/ambiguous frame links remain unverified. Mac compositor measurements,
if available, must attach to the recorded measurement PID and align the clock
bridge; do not collect other applications' content or infer presentation from
the Skia draw callback. Native presentation analysis is still a separate gate.

Lifetime observations include JVM ImageIO scratch buffers and final Skia
backings, Android Bitmap allocation byte counts, dimensions, decode concurrency,
composition owners, and weak liveness through close and a GC request. No strong
bitmap references enter the trace. Weak collection is not proof of native or
GPU deallocation. `closed.json` retains pre-GC observations. The memory sampler
uses only the identified desktop test PID or the isolated Android package;
`run.json` identifies PID, head, source-dirty state, material/APK hashes and
sampler errors. The observer and capture tools have overhead; keep them fixed
across before/after runs. Logs contain only opaque session/image/action IDs and
numeric metadata. Trace overflow invalidates completeness.

Portable evidence processing checks:

```sh
python3 tools/reader-cache/test_summarize.py
python3 tools/reader-cache/summarize.py TRACE_JSON --output SUMMARY_JSON
```

## Decoded window and candidate validation

`ReaderPageWindow` owns the current page and its immediate neighbours, with a
serial loader and at most three owned slots including an in-flight load. Visible
pages take priority. Speculation waits for their draw commands; returning from
a source dialog also waits for the underlying viewer's draw before prefetching.
Repeated selections coalesce, obsolete work is cancelled, and non-cooperative
completion cannot publish into a different current selection. Prefetch failures
are retried when promoted to foreground; foreground failures retain explicit
retry. Stale saved positions hold speculation until explicit navigation.

`ReaderImageWindow` shares that controller only among active viewers of the same
`ReaderHttp` instance. Keys include material ID/version, page and the current
native presentation/decode profile. Nested question-source dialogs reuse ready
rasters and restore their underlying reader; inactive viewers do not compete
for loading. The last close cancels the scope and clears cache ownership. Native
decodes across closing/reopening scopes share a serial gate. No displayed bitmap
is recycled while a renderer may still reference it. Consequently, the three
managed slots do not establish the native/GPU peak budget; use the existing
liveness/process evidence and keep that acceptance gate open.

Portable window behavior tests (no corpus or UI test compilation):

```sh
tools/dev/heavy ./gradlew :shared:client:jvmTest \
  --tests com.coach.client.ReaderPageWindowTest
```

Repeat the `run_observed.py` performance commands above using fresh candidate
output directories and the baseline's exact AVD, window, SDK, density, fontScale
and Poppler configuration. The performance suite preserves the 42 pointer/pixel
samples. It now emits hit/miss/unknown groups and prefetch-ready counts, with
publication-before-input used for a hit; an in-flight prefetch is a miss.
Both citation opens in that suite are cold because the last viewer closes
between them. Measure actual cached question-source reuse separately:

```sh
tools/dev/heavy --local python3 tools/reader-cache/run_observed.py \
  --platform desktop --suite behavior --output build/reader-observed/desktop-cache-path
# Use the same Android parameters as the performance command, adding --suite behavior
# and a new output directory. The class is ReaderPageCachePathSmoke.
```

The behavior suite uses the production catalog/book view, turns to a prefetched
page, opens an already cached source page, closes it and checks the original page
returns without another image request/decode. It asserts trace hit evidence and
target pixels through the real reader/HTTP/platform-decoder path. Its two timed
actions are reported separately from the 42-sample performance suite.

The candidate performance hosts retain `before-close.json` and `closed.json` in
the same live process and hold each lifecycle phase for 600 ms **after** its
latency sample, so RSS/PSS sampling can observe before-close, after-close and
after-GC-request states. Unique run IDs exclude stale phase files. These holds
do not enter page-turn timings. For a matching lifecycle comparison, the same
observation-only additions can be replayed on the uncached baseline without
changing its loading path; preserve both source hashes and patches.

The Mac compilation patch is absorbed with its limits/assertions unchanged.
Windows compilation is not emulator evidence. New native behavior/performance
runs, the missing/ambiguous frame associations, Mac presentation, full service
path and native/GPU memory budget remain required before acceptance. Independent
review and cross-platform verification follow the candidate evidence.
