# Reader page cache measurements

Baseline production source: `1014caf0f57d89bf7b937e8eaa8cd1236675c677`.
At that revision, `PdfPageImage` requests and decodes on every page change.
The original AWT baseline is preserved at PR141 head `5f7062c`. The measured
Poppler baseline is `27f07f8` plus the four named `waitUntil` argument fixes from
the Mac evidence package. Current code implements the three-page decoded window;
the first candidate (`880f99c`) had 40/40 desktop but only 2/40 Android ready
turns. Its Android real-service cached citation took 1598.1 ms to draw commands.
That candidate did not meet acceptance. The revised scheduling/metadata/lifetime
paths below require new measurements; memory/presentation acceptance is pending.
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
decodes across closing/reopening scopes share a serial gate. Image requests and
publication use an independent worker dispatcher, rather than the pausable
composition context. Native decode cancellation retains its result until the
controller can publish or retire it. Composition acquires a counted raster use,
so cache eviction waits for every composed use before releasing our Skia handle
or recycling an unused Android bitmap. A drawn Android bitmap remains under
renderer/GC ownership; `release_deferred_renderer` explicitly records that gap.
No displayed Android bitmap is recycled while a renderer may still reference it.
Consequently, the three
managed slots do not establish the native/GPU peak budget; use the existing
liveness/process evidence and keep that acceptance gate open.

Portable window behavior tests (no corpus or UI test compilation):

```sh
tools/dev/heavy ./gradlew :shared:client:jvmTest \
  --tests com.coach.client.ReaderPageWindowTest --tests com.coach.client.ReaderRasterLeaseTest
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
returns without another image request/decode, then turns again and checks that
the newer progress revision saved by the source is shared with the underlying
book. Existing pixel/reuse assertions remain. It asserts trace hit evidence and
target pixels through the real reader/HTTP/platform-decoder path. Its three timed
actions are reported separately from the 42-sample performance suite.

While a book remains open, visible source links resolve their targets on a worker.
Only a target matching the active document/version can reuse that book's validated
state. No extra page is decoded by this metadata warming; closing the last viewer
removes its scope. Cold links resolve/catalog in parallel and still load validated
state online. Source/state timing events distinguish this path from bitmap lookup.
The same reader/account scope shares only confirmed progress revisions; it does
not retry or overwrite an external conflict. Metadata readiness before input and
bitmap readiness before input must be identified separately in native reports.
The performance suite still makes immediate turns without a prefetch-readiness
wait, preserving comparison with the previous baseline/candidate.

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

## Early fetch, transport diagnosis and native invalidation

The `ed221f9b` native run still had only 3/40 Android turns decoded before input.
Its 37 late turns finished decoding a median 10.157 ms after input; request to
ready took a median 161.378 ms. Moving the loader to a worker did not fix that
wait. The revised pipeline starts the next neighbour's HTTP request immediately
after the current bitmap is published, overlapping network with the current
draw. It waits for visible draw commands before speculative native decoding;
promotion to a visible page bypasses that wait. Current-page priority and the
three managed slots, including the load, remain enforced. Measure the result;
this timing opportunity is not a claim that the target now passes.

Reader image HTTP uses scoped streaming execution so `http_headers_received`
and `http_body_received` are distinct, without the non-streaming saved-response
copy. Cancellation propagates and a declared body-length mismatch is rejected.
These semantics follow [Ktor response streaming](https://ktor.io/docs/client-responses.html#streaming-data).
Per-request trace tokens match start/headers/body/ready/cancel events. The fixture
records server handler/header/body queue times and its actual TCP_NODELAY value;
queue completion does not prove wire delivery. No URL, header, credential or
material content enters these measurements.

The default `--transport-mode legacy` preserves the prior HTTP/1.0 fixture.
Do not replace historical samples with a different transport mode. Run the new
transport suite on Mac using the existing Android parameters, for example:

```sh
tools/dev/heavy --emulator python3 tools/reader-cache/run_observed.py \
  --platform android --suite transport --transport-mode legacy \
  --host <existing-configured-host> --serial <isolated-emulator> \
  --adb <adb> --aapt2 <aapt2> --apk <isolated-apk> --test-apk <isolated-test-apk> \
  --output build/reader-observed/android-transport-legacy
```

Use fresh output directories for `nodelay` and `keepalive` diagnostic controls,
with identical PNGs and endpoint configuration. These are separate transport
experiments, not comparable replacements for the legacy baseline/candidate.
`transport.json` has 12 CIO image transfers, four HttpURLConnection controls and
four fresh-socket controls,
without decoding or drawing. Headers/body timings and `transfers.json` distinguish
server writes from client continuation; they do not alone identify a TCP cause.
Only continue to full performance reruns once the request stages are understood.
The real-service proxy must retain its own transport settings in the report.

`--suite lifecycle` (both platforms, same runner parameters) drives the production
PDF composable with synthetic book/version/raster variants and changes the local
density profile. It verifies fresh image identities and pixels, returns to the
original book, closes, pauses the next decode, closes again, resumes the late
result and reopens. The pause is **before JNI inside the non-interruptible decode
scope**, not an interruption within a native decoder. Closing waits for both
composition ownership and the window's completed retirement callback. The drain
signal follows disposal of a late result, not just completion of its coroutine.

The version endpoint exists only in this isolated lifecycle fixture. Its second
book and v2 deliberately map to different synthetic PNG pages; this proves
client-side native key invalidation and is not a regenerated PDF or a hot update
of the real candidate ReaderLibrary. Preserve that service-path gap explicitly.

The lifecycle suite records the same PID through idle, two-page-ready,
three-page-ready, closed, closed-with-decode-paused, final-closed and GC-request
phases, plus Android graphics/native-heap summaries. Each phase has a one-second
hold outside performance sampling. `lifecycle-cases.json`, seven phase snapshots,
memory samples, request logs and the final trace are retained. A phase delta,
weak-reference count, or native handle-close event still does not prove the
physical three-image increment or GPU release; those verification flags remain
false.

For Android presentation, `target_window_draw_commands` records **every** draw
on a hardware Canvas, including a later redraw after a dropped frame. Software
Canvas draws cannot create this marker; hardware acceleration alone does not
prove window presentation ([Android rendering checks](https://developer.android.com/topic/performance/views/hardware-accel-views)).
`window_frames.sql` links each such
draw's Choreographer token to candidate app and SurfaceFlinger frames; substitute
the exact `run.json` PID for `{{PID}}`. Retain all candidates. Reject ambiguous
layers, dropped/unfinished frames, and frames outside the action. A later unique
presented redraw is an upper bound on display latency; it is not necessarily the
first presentation. No continuous frame may be inferred merely from an earlier
bitmap draw. Keep clock alignment, trace-quality warnings, absent witnesses and
the missing Mac presentation endpoint visible in the report.

## Barrier driver and connection evidence after 93301ca

The `93301ca` native lifecycle run passed the first six checks on both platforms,
then timed out after reopening with no new window/decode events. The driver
blocked in `runBlocking` immediately after injecting input. It now pumps the UI
through `waitUntil` until the reopened document and decode-pause marker exist,
then obtains the permit token. The original 15-second limit and the cancellation,
retirement, reopen and pixel assertions remain. This driver correction still
needs its native rerun; earlier six-case output cannot stand in for completion.

Darwin reported TCP_NODELAY as raw `4`. The fixture preserves `tcpNoDelay` and
adds `tcpNoDelayEnabled`; assertions check enabled/disabled behavior, not a
platform integer encoding. Payload, protocol, version rejection and server write
ordering checks remain. `connectionId` and `requestOnConnection` identify real
reuse; the fixture tests verify the distinction between persistent HTTP/1.1 and
closing HTTP/1.0 responses. Boolean socket options use nonzero to represent
enabled ([Apple socket option semantics](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/getsockopt.2.html)).

Source inspection found that CIO 3.3.3 defaults to `pipelining=false` and also
chooses a dedicated connection when a plugin connect/socket timeout is present
([engine defaults](https://github.com/ktorio/ktor/blob/3.3.3/ktor-client/ktor-client-core/common/src/io/ktor/client/engine/HttpClientEngineConfig.kt),
[dedicated-request condition](https://github.com/ktorio/ktor/blob/3.3.3/ktor-client/ktor-client-cio/common/src/io/ktor/client/engine/cio/EngineTasks.kt)).
Both apply to the current reader transport. A Windows regression measured three
TCP connections for three HTTP/1.1 images. That explains lack of connection reuse,
but does not establish which portion of Android's pre-header wait is connect,
write, network receipt or selector/continuation scheduling.

A reader-only serial-pool experiment did not pass HTTP/1.0 and repeated-cancel
regressions; it is not in the candidate. Production CIO configuration is retained.
The failed experiment and XML are evidence, not passing tests. Native reports
must keep the pre-header cause unresolved until the new probes run.

The diagnostic order is now `URL4,CIO12,socket4`. Each fresh socket records
connect completion, request write, first response byte, headers and full body,
using the same synthetic endpoint and an explicit `Connection: close` request.
It records the socket's actual client NODELAY setting. These test-host probes
do not replace production transport. They distinguish fresh TCP establishment
from write-to-first-byte waiting; they cannot by themselves attribute a difference
to CIO's selector. Compare matched modes and server connection IDs. Do not
subtract host timestamps from guest clocks, combine controls with UI performance,
or replace historical baseline samples.

## Request/response boundaries after 7d80e92

Both native lifecycle runs at `7d80e92` completed all eight scenarios/seven phases.
The transport controls still measured about 501 ms from socket write to the first
buffered read return on Android, even outside CIO. Do not attribute this solely
to CIO or switch the production engine on that basis.

New transport samples carry a generated `readerProbe` token for each run/kind/page.
The synthetic server echoes it in `X-Coach-Reader-Probe`, together with connection
and request ordinals. All three clients verify their own response echo. Ordinary
reader image requests carry no token and retain their original URL/engine policy.
The probe API is restricted to the synthetic-baseline document. A service adapter
without echo support cannot pass this transport diagnostic; performance/behavior
calls do not require echo support.

`transport-association.json` joins each token to exactly one server row, verifies
echo/status/length/connection and rejects missing, duplicate or incomplete records.
It retains original records and computes intervals only within their own clock:

| Client boundary | Host boundary |
| --- | --- |
| connect return, request write return | accept return, handler start/initialization |
| first read invocation/return | request line read return, headers parsed |
| full headers/body read | response header write invocation/return, body write return |

Socket first-byte sampling now calls the unbuffered `InputStream.read()` before
wrapping the remaining stream. It still observes an application read return, not
guest kernel receipt. Response writes are queue/API boundaries, not wire delivery.
Nonce/header instrumentation changes the diagnostic traffic; these samples must
not replace historical performance data or be described as a speed improvement.

Optional host packet observation is available only for `--suite transport`:

```sh
# Select an existing interface carrying the configured synthetic host; list with tcpdump -D.
tools/dev/heavy --emulator python3 tools/reader-cache/run_observed.py \
  --platform android --suite transport --transport-mode legacy \
  --packet-interface <existing-interface> \
  --host <existing-configured-host> --serial <isolated-emulator> \
  --adb <adb> --aapt2 <aapt2> --apk <isolated-apk> --test-apk <isolated-test-apk> \
  --output build/reader-observed/android-request-boundaries
```

The runner starts and joins its own tcpdump process. The filter fixes the exact
new fixture IPv4/port in both directions; callers cannot supply a general filter.
No sudo, BPF permission changes, production ports or services are used. Missing
access/readiness/packets/decode produces an explicit failed requested-capture
result while retaining the application records. With no interface requested,
capture is marked unobserved. Windows tests mock capture and do not claim Mac
BPF availability.

The output adds `wire.pcap`, `wire-text.log` and `wire-capture.log`; `run.json`
records the exact filter/PID, exit, packet count and host monotonic/epoch clock
brackets. These packets contain only the owned synthetic service traffic. Match
query tokens and response echo headers in the packet stream, then use TCP ports,
sequence numbers and server connection ordinals. Retain retransmissions/ambiguous
or split headers as candidates, rather than declaring one packet uniquely matched.
Host wire timing and host application timing can locate accept/handler/write gaps;
they do not prove guest kernel receipt or an offset between host and guest clocks.

Start with one matched Android legacy run and a Mac reference when needed. Inspect
whether the gap occurs before the request reaches the handler, during header
write, or outside the host-observed request/response interval. Keep unresolved
clock/scheduling/network gaps explicit before deciding to rerun performance.
The existing first-presentation, trace-quality, real hot-update and physical/GPU
budget gaps remain open.

## Socket destination control after bd8fa45

At `bd8fa45`, Android legacy transport matched all 20 request/response tokens.
For every request, host accept-return through header-write-return was at most
9.708 ms. One socket's write-to-unbuffered-read-return was 501.432 ms while its
matched host accept-to-header-write interval was 1.564 ms. These durations do
not locate the gap before accept or after the response write. BPF access failed
with permission denied; the requested capture runner correctly failed even
though the instrumentation and token association passed. Do not change BPF
permissions or attribute this delay to CIO.

The next minimal experiment is Android-only `--suite route` with legacy framing:

```sh
tools/dev/heavy --emulator python3 tools/reader-cache/run_observed.py \
  --platform android --suite route --transport-mode legacy \
  --host <existing-configured-host> --serial <isolated-emulator> \
  --adb <adb> --aapt2 <aapt2> --apk <isolated-apk> --test-apk <isolated-test-apk> \
  --output build/reader-observed/android-socket-routes
```

No packet interface is requested. The original fixture listens on the exact
configured IPv4. This suite alone creates an additional listener on host
`127.0.0.1` at the same port, sharing the same Handler, image bytes and connection
counter. Both listener threads and request workers are joined on exit, including
failure. It never binds all interfaces. The emulator reaches host loopback via
the fixed `10.0.2.2` alias ([Android address-space documentation](https://developer.android.com/studio/run/emulator-networking-address)).
The generated app network policy, production URL and CIO engine are unchanged;
only this test's raw sockets use the alias. There is no adb redirection, route,
VPN, firewall or system configuration change.

The test creates 16 fresh sockets, eight per destination, in four blocks:
`ABBA,BAAB,ABBA,BAAB` (`A=configured`, `B=host-loopback`). Each block requests one
identical PNG page. Only the socket destination changes; HTTP Host, port,
credentials, framing, read method and NODELAY settings stay fixed. Every request
has its own token and verified echo. Body hashing occurs after the transport
end timestamp. Completed rows are saved after every request so a later failure
does not erase partial evidence.

`transport-association.json` retains each original sample and host/guest
intervals separately. New `acceptCalledNs` records the accept invocation after
the listener's readiness loop; it is **not** SYN arrival or accept-queue entry.
`route-comparison.json` requires 16 unique associations, the exact sequence,
eight samples per route, distinct fresh server connections, the expected
listener/port and identical per-block payload hashes. It reports p50, nearest-rank
p95 and maximum per route for each measured interval. Missing alias access,
changed payloads, wrong listeners or partial records fail the experiment;
they do not become slow or passing samples.

A difference can show dependence on destination/listener path under this emulator
configuration. It cannot isolate a particular VPN, virtual router, network
stack, kernel receipt or scheduling component. A similar delay on both routes
leaves those hypotheses unresolved. Keep all samples and connection identities;
do not subtract host and guest timestamps, mix these controls into historical
UI performance, or change production transport based on this experiment alone.
Collect this single route run before deciding on another performance run.
Windows tests verify listener cleanup and reject broken associations with
invented unit payloads; actual Android alias access still requires the Mac run.
