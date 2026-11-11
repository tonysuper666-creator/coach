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

## Observer control after 9ffe099

The native `9ffe099` route run completed 16 unique associations with matching
payloads. Both destinations still had about 500 ms median write-to-read-return
latency, while host accept-to-header-write medians were about 1.2 ms. This route
change did not remove the gap. It does not identify a shared network component.

The dual-listener unit test used an unconfigured `127.0.0.2` address that Darwin
rejects. It now uses two distinct ephemeral ports on configured `127.0.0.1` via
`loopback_ephemeral=True`, retaining real requests, two distinct listeners,
hash/echo/connection checks and joined thread/socket cleanup. The native route
experiment still uses its two exact configured/loopback addresses at the same
port; it does not enable this unit-fixture option. No address aliases, test
skips or system changes are needed.

The next minimal shared-path hypothesis is observer interference. Every previous
Android run also issued repeated `run-as` stage reads and `dumpsys meminfo` calls
from the memory sampler. Their effect is unmeasured. Add
`--no-transport-memory-sampler` only to an Android `transport` or `route` run to
omit that thread entirely. The default remains enabled. UI performance/behavior
and lifecycle suites reject the flag so it cannot remove required memory evidence.
`run.json` explicitly records `memorySamplerEnabled`, `memorySamplerStarted`
and `memorySamplerJoined`; an off run has no sampler memory file and provides
no memory evidence. Setup, instrumentation and post-test extraction still use adb.

Use **one isolated AVD session** for three sequential foreground invocations with
fresh output directories, the same APK hashes, PDF, renderer, geometry and route
suite. Change only the observer flag: **A1 on → B off → A2 on**. Do not request
Perfetto or packet capture in any group. For example, repeat the preceding route
command, adding `--no-transport-memory-sampler` only for B. Each invocation must
still pass all 16 identities and both eight-sample routes. Record PID, individual
samples, first-block transients, sampler flags and memory-file presence. Preserve
every group; do not compare B only to a historical A with a different environment.

This reversal tests the combined repeated stage/memory observer, not an individual
adb command or scheduler mechanism. A reproducible change would justify narrowing
that observer further; no change would leave other common-path hypotheses open.
Keep host and guest intervals separate. These are transport controls with no
decode/draw and cannot replace the fixed UI baseline or establish 100 ms,
first presentation or a three-image physical/native/GPU budget. Collect this
small control before deciding on a performance rerun. Production code and native
test workload are unchanged in this revision.

## Separating observer operations after 411bc51

At `411bc51`, Mac Python passed all 23 checks. In one AVD, the on/off/on route
experiment completed 16 associations per group. The two route medians fell from
about 501 ms with the combined observer to 126/219 ms with it off, then returned
to about 501 ms. The off group still had substantial waiting. This single
reversal supports an observer effect; it does not isolate a command or explain
all waiting. Keep its samples separate from the UI baseline.

`--transport-observer` now independently selects the repeated Android operations:

| Mode | Stage file read | dumpsys meminfo | Observer output |
| --- | --- | --- | --- |
| `both` (default) | yes | yes | `memory.jsonl` |
| `stage` | yes | no | `stage-samples.jsonl`; no memory data |
| `meminfo` | no | yes | `memory.jsonl`; stage is unobserved |
| `none` | no | no | no observer file or thread |

The old `--no-transport-memory-sampler` remains an alias for `none`; specifying
both flags is an error. All overrides, including explicit `both`, are limited
to Android transport/route diagnostics. UI performance/behavior, lifecycle and
desktop suites retain their default complete observation. The native test,
route/payload/echo/connection assertions and production transport are unchanged.

`run.json.androidObserver` records the mode, stage/meminfo selections, actual
command counts, output filename and 0.2-second wait **after each cycle**. Every
observer row records each selected command's host invocation/return timestamps
and exit status, including failed/missing stage reads. A stale/malformed stage
cannot invent a matching run or phase. Command exceptions retain their failed
span and fail the runner after joined cleanup. Different modes can finish cycles
at different rates, so retain their actual counts and timings; they are not
equal-frequency interventions. Setup/instrumentation/post-test adb remains.

For compatibility the older `memorySamplerEnabled/Started/Joined` fields describe
the observer thread, so stage-only also reports three true values. They do not
prove memory sampling. Use `androidObserver.meminfoEnabled` and actual meminfo
rows to distinguish memory data. Stage-only and none supply no memory evidence;
meminfo-only has no phase correlation. No diagnostic mode proves a physical/GPU
budget.

In one isolated AVD session, collect the smallest four-condition comparison
with a repeated reference: **both → stage → none → meminfo → both**. Use five
fresh output directories, the same APK hashes, PDF/Poppler, geometry and route
legacy workload; add only `--transport-observer <mode>` to the preceding command.
No Perfetto or packet capture. Each group must retain all 16 token/payload/
listener associations, both eight-sample routes, transient values, PID, command
counts, command statuses/timings and joined cleanup. The two `both` references
help detect drift; this is one ordered pass, not counterbalanced replication of
the observer modes. Do not pool groups or attribute causality to an individual
command from one order alone.

Compare each route's write-to-read interval across conditions and inspect which
operations actually ran. A stage-only or meminfo-only pattern can narrow the
next hypothesis; an interaction can require both operations. Even none may leave
waiting elsewhere. Host command/server intervals share a clock, but do not
subtract host and guest timestamps or claim kernel receipt. Decide on any UI
performance rerun only after this control; the fixed baseline, presentation,
trace-quality, real-hot-update and native/GPU gaps remain open.

## Unmatched phases and reversed order after c0daf35

The `c0daf35` five-condition run passed all 80 associations and Mac's 30 Python
checks. Only stage-only was lower (route medians 13/160 ms); none, meminfo-only
and both references were still about 501 ms. Thus disabling the observer did
not consistently remove waiting. The previous on/off/on observation remains
evidence for that round, not a stable observer or single-command explanation.

All 78 stage reads exited zero but failed run matching. Source inspection found
that the transport test never wrote `reader-stage.json`; only other reader
measurement suites wrote it. A previous cache file is a plausible explanation,
but the old observer rows did not retain its body or run fingerprint, so they
cannot identify which earlier run produced it. None of those 78 rows establishes
the current phase. Exit zero and a joined observer do not repair this gap.

The Android transport test now atomically writes its actual run/suite/PID with
`transport_running` before probe timing, then `transport_complete` or
`transport_failed` after it. These two writes are outside the individual request
intervals and apply to every observer mode. It does not retag an old file or
write per-request stage markers. **Rebuild both isolated APKs for this head**;
the old reused instrumentation binary lacks the writer.

Observer rows now distinguish matched, stale-run, wrong-suite, invalid-PID,
invalid-phase, malformed and failed-command reads. A hash of an observed run ID
helps compare stale reads without retaining the old file body. Transport/route
diagnostics additionally validate suite and PID structure; default UI/lifecycle
stage matching remains compatible with its existing writer. `stage-audit.json`
is produced after the observer joins and the measured PID is extracted. When
stage reading was selected, at least one `transport_running` row must match the
current run/suite and the measured PID. Otherwise the runner fails while
retaining request associations and phase-audit results. Pre-start stale reads
are retained and counted, not relabelled. None/meminfo-only report stage audit
as not requested/unverified; they do not invent a phase witness. This is coarse
test-phase identity, not per-request display or memory-budget evidence.

Use one isolated AVD and identical newly built APK hashes/PDF/Poppler/geometry
for the minimum reversed-order repetition: **stage S1 → none N1 | none N2 →
stage S2**. The two adjacent pairs have opposite mode order; each mode appears
twice. Each group uses the route legacy workload with a fresh output directory,
changing only `--transport-observer stage|none`. Do not request Perfetto or
packet capture. Keep all 16 associations, both eight-sample route distributions,
first-block transients, PID, sampler flags, phase-audit outcomes, actual operation
counts/statuses/times, and cleanup for every group. Neither mode provides memory
data. Report stage observation frequency with its explicit first-invocation to
last-return denominator, plus current-active-phase counts; setup/extraction
observations and after-cycle waits are not equal-frequency probe exposure.

Compare the S1/N1 and N2/S2 route results separately, retaining direction and
all values; do not pool them into a latency acceptance number. This is one AB
and one BA pair within one AVD, not replication across independent device
sessions. A reversal in the apparent effect leaves the explanation unstable.
The new phase writes change diagnostic instrumentation, so do not merge this
head with older round samples. Keep host and guest clocks separate. Decide on
performance reruns from this result; the original baseline, real presentation,
trace quality, real hot update and native/GPU budget gaps are unchanged.

## Application-readable bytes after fa45e50

The native `fa45e50` phase audit passed. Stage/none effects still did not repeat
consistently: N1 was lower, while the identical none mode in N2 was about 501 ms.
Per-request review puts write-return to read-invocation at at most 0.028 ms and
host accept-return to header-write-return at at most 1.924 ms across N1/N2.
The main difference remains within read-invocation to its application return
marker. Host writes do not establish guest receipt, and a read return marker
does not identify when that thread became runnable.

The next optional diagnostic is `--socket-queue-watch`, accepted only with
Android route and explicit `--transport-observer none`. The observer thread is
started and confirmed ready before writing the request. After write-return it
queries **the same unbuffered input stream's** `available()` without consuming
bytes, while the original thread performs its ordinary blocking first-byte
read. Each query retains invocation/return timestamps and estimated readable
bytes; the result also records actual stream class, read/observer TIDs, startup
and joined cleanup. Querying stops after the first-read marker or 512 calls,
with a requested 2 ms park between calls. This is not a guaranteed frequency.
Socket-close fallback and query exceptions make the requested diagnostic fail;
all owned threads are joined before returning the request report.

[`available()` is an estimate of nonblocking-readable bytes](https://developer.android.com/reference/java/io/InputStream#available()).
It is **not** a packet-arrival timestamp, and zero is inconclusive. A positive
query return recorded before the main read-return marker shows that the other
thread could observe application-readable bytes on this connection earlier.
Those may include remaining response bytes; this does not locate the first
byte's kernel arrival or prove an application wakeup cause. Query invocation is
not substituted for query return: a query may itself be delayed by scheduling
or stream locking. Keep positive-before-read-call and positive-during-read cases
separate. Late returns, zero/empty query sets and limit-reached cases remain
inconclusive, not evidence of no received packets.

`queue-watch.json` validates observer identity, joined ownership, query order and
the existing request associations. It retains every request's classification
and any positive-observation-to-read-marker interval in the same process clock.
Missing observer records (including an old APK ignoring the new argument), errors,
forced socket closure or invalid timing fail the requested diagnostic. Ordinary
route commands retain the blocking-read path without the new thread; UI/default
memory sampling and production transport are unchanged.

Rebuild both isolated packages and use one AVD, identical hashes/PDF/Poppler and
fresh output directories. Collect **plain P1 → watch W1 | watch W2 → plain P2**,
all with `--suite route --transport-mode legacy --transport-observer none`, adding
only `--socket-queue-watch` in W1/W2. No Perfetto or packet capture. Keep each
group's 16 token/hash/listener/connection checks, both eight-sample route
distributions, individual query traces/counts/gaps, thread IDs and join/close
status, first-block transients, PID and cleanup. Both modes supply no memory data;
their phase audits remain explicitly not requested.

The new monitor can perturb scheduling and waiting. Compare both order pairs
separately, without pooling into acceptance or claiming a speedup. An early
positive witness can narrow the location of part of the observed wait; absent
witnesses cannot distinguish transport delay from observer/read scheduling.
This small application-boundary experiment does not replace kernel packet
evidence, the original performance baseline or actual presentation/native/GPU
budget validation. Keep previous phase, lifecycle and other open gaps unchanged.

## Read-thread scheduling after 66c2bb0

The two native early-positive observations were only 1.109167 ms before a
6.242792 ms read marker and 0.076917 ms before a 501.707167 ms read marker.
They do not bound a large early-readable portion of the long wait. W1's lower
latency did not repeat in W2. In four recent, separately configured rounds the
lower group was second in the invocation order despite different treatments.
That is an ordinal/state clue, not pooled evidence for a command or monitor cause.

The next plain-route diagnostic is `--socket-scheduling-trace`, requiring Android
route with explicit `--transport-observer none`. It is separate from queue watch
and the existing FrameTimeline `--perfetto` flag. It owns a 15-second foreground
Perfetto session using `socket-scheduling.pbtxt`, with scheduling/waking, task
identity, application trace markers and process identity polling. No packet or
syscall trace, root, permission or system configuration change is requested.

The new instrumentation must be rebuilt. It verifies tracing is enabled and
brackets the ordinary first-byte read with `ReaderSocket/first-read/<probe>` on
the actual reader TID. Labels are also retained in request JSON; an old APK
silently ignoring the argument cannot pass. The bracket includes trace/read
wrapper work through the application return marker; it is not an exact syscall
or packet interval. Capture output is `socket-scheduling.perfetto-trace`, not a
FrameTimeline witness. Capture exit zero alone leaves scheduling verification
false until the following analysis.

[`sched_switch` and `sched_waking`](https://perfetto.dev/docs/data-sources/cpu-scheduling)
support CPU execution/dispatch state analysis. Using the measured PID from
`run.json`, replace `{{PID}}` numerically in `read_slices.sql` and
`read_thread_states.sql`. Execute those and `read_trace_quality.sql` against the
new trace with the existing Trace Processor, saving each CSV with its header.
Keep every candidate and every nonzero quality row. Then run:

```sh
python3 tools/reader-cache/read_scheduling.py --run <run-directory> \
  --slices <read-slices.csv> --states <read-thread-states.csv> \
  --quality <read-trace-quality.csv>
```

`read-scheduling.json` requires one marker per token on the measured PID/TID,
finished durations and contiguous, non-overlapping state coverage of each read
bracket. Errors/data loss fail verification; retain missing identity, coverage
and setup/parser failures. It preserves raw states, optional blocked-function/
waker fields and durations. Long R/R+ intervals can locate CPU dispatch waiting;
S/D or other states do not by themselves identify socket receipt, a lock or a
specific wakeup source. Reader durations and trace state intervals remain in
their own clock domains; no host/guest timestamp subtraction is used. Windows
tests use SQLite tables shaped like the exports and fake capture processes, not
actual Perfetto imports or native scheduler events.

Confirm requested waking/switch events are actually available before interpreting
their absence; zero Runnable time alone does not exclude missing wakeup evidence.
Process-stat identity polling can include other process counters and supplies no
decoded-image/native/GPU budget proof. Earlier FrameTimeline quality gaps are
not cleared by collecting a different trace.

Use a single isolated AVD and identical newly rebuilt APKs/PDF/Poppler/geometry
for **three identical sequential groups T1 → T2 → T3**, each with:

```sh
tools/dev/heavy --emulator python3 tools/reader-cache/run_observed.py \
  --platform android --suite route --transport-mode legacy \
  --transport-observer none --socket-scheduling-trace \
  --host <configured-host> --serial <owned-emulator> \
  --adb <adb> --aapt2 <aapt2> --apk <isolated-apk> --test-apk <isolated-test-apk> \
  --output <new-directory-for-each-group>
```

The surrounding owner holds the emulator lock once across the session, as with
the prior wrappers; do not nest the lock. Keep all 16 request associations and
two eight-sample route distributions per group, PID/TID, markers, state rows,
quality records and cleanup. Do not enable queue watch, repeated adb observation,
FrameTimeline or packet capture. Process polling and trace markers are themselves
observation changes, held identical across these groups. The repeated sequence
checks the ordinal clue without changing treatment. No group is a latency or
memory acceptance run, and prior second-group values are not pooled with it.
Native event availability, exact scheduler coverage and real Trace Processor
queries remain unverified until this run. Old presentation/trace-quality,
baseline, lifecycle, real hot-update and native/GPU conclusions remain separate.

## Blocking operation after the 9bd0d1b scheduling capture

The three native scheduling traces passed 48/48 marker identities and continuous
state coverage. Long read brackets were predominantly S, with sub-millisecond
Running and Runnable maxima. T2's final four brackets shortened to about 159,
14, 16 and 97 ms. Their recorded wakers included ksoftirqd, kworker and dhcpclient;
thread names do not identify why a read blocked or when its socket received data.
The quality reports retain compact scheduler skips and clock-sync info rows.

The next question is which returned system call contains that sleeping time:
polling, receiving, a futex, or another operation. Add `--socket-syscalls` to the
existing scheduling capture for **one initial group of 16 route requests**:

```sh
tools/dev/heavy --emulator python3 tools/reader-cache/run_observed.py \
  --platform android --suite route --transport-mode legacy \
  --transport-observer none --socket-scheduling-trace --socket-syscalls \
  --host <configured-host> --serial <owned-emulator> \
  --adb <adb> --aapt2 <aapt2> --apk <isolated-apk> --test-apk <isolated-test-apk> \
  --output <new-directory>
```

As before, the surrounding owner holds the emulator lock once, without nesting.
Keep the same API35 Pixel Fold image, geometry, PDF and Poppler. Native sources
are unchanged by this addition; the verified 9bd0d1b APK pair may be reused after
checking both hashes against that run. Do not add queue watch, repeated adb
observation, FrameTimeline or packet capture. UI/lifecycle default sampling
requirements are unchanged.

The recorded emulator fingerprint is already userdebug.
[Perfetto syscall tracing](https://perfetto.dev/docs/data-sources/syscalls)
records entry arguments and exit return values on Android userdebug builds.
The runner checks `ro.build.type` before installation and rejects other builds;
do not change image, permissions or root state. `socket-syscalls.pbtxt` adds only
raw syscall entry/exit events to the scheduling sources, retaining the 15-second
foreground session and using a 64 MiB ring buffer. The larger event stream can
perturb timing or overflow: inspect event availability, all quality records and
state coverage before interpretation. This group is not a performance rerun.

The new output is `socket-syscalls.perfetto-trace`. Against **that same new trace**,
first rerun `read_slices.sql`, `read_thread_states.sql`, `read_trace_quality.sql`
and `read_scheduling.py` as above. Then replace `{{PID}}` in `read_syscalls.sql`
and `read_syscall_args.sql` with the measured PID and execute both queries with
the existing Trace Processor. Retain both complete CSV exports and SQL/logs.

```sh
python3 tools/reader-cache/read_syscall_analysis.py --run <new-run-directory> \
  --syscalls <read-syscalls.csv> --quality <read-trace-quality.csv>
```

`read-syscalls.json` validates read labels and PID/TID/UTID, returned call
durations, exit values and non-overlapping call intervals, then overlays the
same thread's S/D state intervals. Missing calls, unfinished non-marker calls,
identity errors, scheduling gaps and error/data-loss quality rows fail the
requested analysis. Its `complete` field means those records passed structural
checks; inspect each `unattributedSleepSDNs` and `sleepAttributionComplete`
separately. Residual sleep remains unknown even if other calls are valid.

All `sys_write` slices are retained as boundary records: the
[Perfetto importer](https://android.googlesource.com/platform/external/perfetto/+/bae0257cc5901c9368ae25ee1d4acc586390167c/src/trace_processor/importers/ftrace/ftrace_parser.cc)
handles trace-marker writes specially, so these slices are not socket-send
evidence. The raw-argument export preserves every argument without dereferencing
pointers. In particular, poll's first argument is not a verified socket FD.
Even a returned receive call with an FD-shaped argument does not establish its
connection identity or kernel packet-arrival time. Negative returns, retries,
boundary calls and unknown sleep stay visible. A sleeping poll/read can narrow
the blocked operation; it still cannot identify a network component's cause.

Windows validation uses SQLite tables shaped like Trace Processor exports and
mocked collectors. Real syscall import, event support and native results remain
unverified until the Mac capture. Preserve all 16 token/hash/listener/connection
checks, both route distributions, first-block and final-block transients, raw
states/wakers/arguments, nonzero quality rows, APK hashes and cleanup. Choose any
further capture from that result, before another performance run. No memory,
presentation, real hot-update or native/GPU budget conclusion changes here.

## Signal boundary and virtual Ethernet after 6f37f74

The first native syscall capture retained all 16 request/state associations.
Every read had a returned ppoll of about 500 ms containing its recorded sleep,
followed by recvfrom returning one byte in about 0.004-0.041 ms. A ppoll return
of one counts a ready element; it does not identify POLLIN without the returned
event bits. See the [poll/ppoll interface](https://man7.org/linux/man-pages/man2/poll.2.html).

Route-9 also contains a zero-duration `sys_rt_sigreturn` without a paired ret,
after both the long ppoll and the one-byte receive. Its raw entry number is 139;
the nearby raw exit number is -1. Signal restoration resumes the interrupted
context rather than returning as an ordinary call
([sigreturn](https://man7.org/linux/man-pages/man2/sigreturn.2.html)). The arm64
[kernel restoration path](https://github.com/torvalds/linux/blob/v6.6/arch/arm64/kernel/signal.c)
also clears syscall context. This is compatible with that boundary shape; it
does not prove the exact emulator kernel/importer implementation or signal cause.
Do not pair the -1 exit with entry 139 or invent a return value. The analyzer
adds an explicit unpaired-signal note but keeps the strict local error and
overall `complete=false`. Zero residual sleep never clears this failure.

The next minimal capture is one identical 16-request route group, with the
same scheduling/syscall settings and APK hashes, adding only the emulator's
documented [`-tcpdump <file>` startup option](https://developer.android.com/studio/run/emulator-commandline).
This was the cdccbe4 experiment. Its empty fixture capture did not establish
Wi-Fi coverage; use the netsim-specific correction below for the current image.
It captures virtual Ethernet packets and does not require opening the host BPF
interface. Keep the existing image, account/network policy and permissions.
Use the current Mac owner/foreground emulator lock, geometry, PDF and Poppler.
Do not start an emulator on winbox. Reuse the verified native packages; the
new code here is offline Python and documentation only.

Before starting the owned AVD, choose a new capture path inside this checkout:

```sh
# Additional argument to the existing owned emulator launch, not a second AVD:
-tcpdump <checkout>/build/reader-packets-<session>/local-only.pcap
```

Run one `run_observed.py` group with the same route legacy, observer none,
`--socket-scheduling-trace --socket-syscalls` arguments. No queue watch, live
host packet-interface capture, repeated adb sampling or UI performance suite.
Join Perfetto, stop only the owned emulator, wait for its exit and retain its
exact launch/version/PID/exit before reading the now-closed capture. Startup
and file output are new observation overhead; this is not a speed comparison.
If this option is unsupported, preserve the failure without changing privileges.

The unfiltered capture includes other Ethernet traffic and stays local. Prepare
shareable fixture-only output **after the owned emulator has stopped**:

```sh
python3 tools/reader-cache/filter_emulator_capture.py --run <new-run-directory> \
  --capture <checkout>/build/reader-packets-<session>/local-only.pcap \
  --emulator-stopped
```

This helper uses only offline `tcpdump -r`, with the recorded exact configured
and alias addresses and the isolated fixture's ephemeral port. It retains the
original, produces `emulator-fixture.pcap`, a sequence-numbered ASCII decode,
tool logs and `emulator-packet-filter.json`, and refuses existing outputs or an
input changed during decoding. Failures remain explicit. Decoder exit zero
alone leaves request association and packet-stream completeness **unverified**.
Do not attach the unfiltered original. Windows tests mock the offline decoder;
they do not exercise an emulator or real pcap decoding.

For each of the 16 requests, associate the packet connection and TCP sequence
with its request nonce and echoed response nonce; retain all candidates, header
reassembly, retransmissions, missing segments and ambiguous matches. Guest local
ports are supplied as hints, not assumed identical to host/NAT peer ports. Keep
SYN/sequence evidence establishing the first response byte; an ACK or the packet
where an assembled header becomes recognizable is not that endpoint. Report
request-final-byte to first-response-byte intervals in the **pcap clock only**,
alongside the separate ppoll/application intervals. A virtual Ethernet trace is
not a guest-kernel receive timestamp. No cross-clock timestamp subtraction or
subtraction of unrelated interval medians can locate an unobserved component.

First collect this boundary evidence and repeat the scheduling/syscall SQL on
its matching new trace, preserving the strict signal/quality failures. Choose
further diagnosis from the result before a performance rerun. The baseline,
100 ms actual presentation and physical three-image/native/GPU gaps remain open.

## Capture coverage after cdccbe4: netsim Wi-Fi

The native run used emulator 37.2.13.0; its log records initialized netsim Wi-Fi.
The QEMU Ethernet file contained 91 packets: 59 IPv4, 28 IPv6 and four ARP. Of the
IPv4 packets, 37 had protocol TCP. The reported fixture-port and nonce counts
were both zero, and all 16 packet associations failed. This is missing target
coverage, not evidence of no TCP, no application traffic or zero network delay.
The raw all-traffic file stayed on the Mac; Windows rechecks the supplied
aggregate and empty filtered file, not that private original.

[Android's console reference](https://developer.android.com/studio/run/emulator-console#network)
distinguishes modern netsim Wi-Fi from legacy QEMU/slirp console capture.
The observed version/log/zero-target result is consistent with a capture-path
mismatch; it does not prove the installed QEMU implementation or a 500 ms cause.
The prior `-tcpdump` candidate must not be described as verified Wi-Fi coverage.
The documented Wi-Fi candidate is
[`-netsim-args="--pcap"`](https://developer.android.com/studio/run/emulator-networking-advanced),
which writes a separate capture per radio chip. This version's actual support,
file link type and target visibility remain unverified until the next capture.

Before another native group, the Mac owner must establish capture ownership:

1. Under the existing foreground emulator lock, record pre-existing netsim
   processes and the owned emulator's exact launch/version/PID. Do not attach
   capture to, reconfigure or stop a daemon owned by another session. If exclusive
   ownership cannot be established, return that gap before running probes.
2. Give the child launch a fresh checkout-local `ANDROID_TMP` directory and add
   `--pcap` to its existing `-netsim-args` value. This is a process environment
   setting, not a global setting. Retain the same image, Wi-Fi/network defaults,
   AVD geometry, native APK hashes, PDF and Poppler. Do not disable netsim, switch
   to cellular, change root/permissions or introduce new delay/speed settings.
3. Retain the actual netsim executable/version, PID/start/parent/command and its
   emitted artifact path. `ANDROID_TMP` chooses artifact storage; it is **not**
   proof of a new or exclusive daemon. Confirm the daemon/session and Wi-Fi chip
   belong to this owned AVD, with files beneath the fresh directory. A filename
   containing "wifi", an old chip or a Bluetooth capture is insufficient.
4. Only then collect one 16-request original route legacy/observer none group
   with the same scheduling/syscall trace. No performance suite, queue watch or
   repeated adb sampler. Stop/join the owned emulator and close its owned capture
   writer before offline reading; record cleanup for both. Never kill unrelated
   netsim processes. Unsupported flags, wrong ownership, missing Wi-Fi captures
   or unsupported link types are explicit failures, not reasons to change setup.

Example additions to the existing owner wrapper, using a new task directory:

```sh
# Child environment only; preserve the existing SDK environment and launch args.
ANDROID_TMP=<checkout>/build/reader-netsim-<session>/tmp
# Append to existing netsim args if any; do not discard them.
-netsim-args="--pcap"
```

The documented artifact layout is below that temporary root in
`android[-<user>]/netsimd/pcap/`. Use the actual emitted path and chip receipts;
do not search shared temporary directories or assume a daemon implementation
from the directory name. Preserve raw per-chip files locally. After ownership
and writer-close checks, extract only the owned Wi-Fi chip file:

```sh
python3 tools/reader-cache/filter_emulator_capture.py --run <new-run-directory> \
  --capture <owned-netsim-wifi-pcap> --emulator-stopped \
  --capture-source netsim-wifi
```

The helper records the **declared** source, with `captureOriginVerified=false`.
Offline decoding alone cannot establish daemon/chip ownership or path coverage;
deliver the separate launch/chip/file/cleanup receipts. Exact fixture filtering,
fresh outputs and input stability guards stay in force. Use the decoder's actual
link type; unsupported Wi-Fi encoding or an empty filtered capture stays failed.
Do not relabel a QEMU/other-radio capture or pass a Bluetooth file as Wi-Fi.

For all 16 requests, retain token/echo/TCP-sequence candidates, reassembly,
retransmissions, gaps and ambiguity. Only uniquely associated target streams
can supply packet-clock request-final-byte to first-response-byte intervals.
Packet timestamps are at the declared virtual-radio capture boundary, not
guest-kernel arrival. Keep packet and guest clocks separate. No packet counts,
decoder success or capture-source label prove network cause, presentation or
memory acceptance. Review coverage first, then choose any performance rerun.
The strict signal-return failure and all earlier acceptance gaps remain open.

## Host boundary witnesses after F2

F2 independently repeated 16 exact packet/application associations. Each complete
request was acknowledged early at the captured TCP boundary (median 0.0965 ms),
while request-final-byte to response-first-byte medians were about 500 ms on both
routes. The long gap is visible in the recorded virtual-radio packet timeline,
as well as in guest ppoll. An ACK is not host application receipt. The small host
accept-to-header-write duration cannot locate that long gap without compatible
timestamps. F2's later signal restoration still fails strict syscall validation;
do not clear it or compare unmatched F1/F2 medians as a causal estimate.

The next addition is `--host-clock-boundaries`, allowed only with the isolated
Android route/observer none/scheduling/syscall diagnostic. Keep F2's owned netsim
Wi-Fi capture, geometry, image, APK hashes, PDF and Poppler. Collect one group,
adding only this fixture-side observation flag to the runner:

```sh
--platform android --suite route --transport-mode legacy --transport-observer none \
  --socket-scheduling-trace --socket-syscalls --host-clock-boundaries
```

For each nonce-correlated transfer, `hostClock` records the fixture PID, clock
implementations/resolutions and four near-boundary samples: accept returned,
request headers parsed, response header write started, and header write returned.
Each epoch read has monotonic reads before/after it. The existing monotonic event
timestamps remain present; the nearby epoch sample is not an exact syscall event.
HTTP headers, image bytes, transport policy and existing request guards are
unchanged. The extra clock reads can perturb timing; this is not a performance
acceptance run. Ordinary UI, transport and lifecycle commands do not enable it.

[Python documents](https://docs.python.org/3/library/time.html) epoch time and
monotonic clocks separately. `host_clock.offset_bounds` computes the arithmetic
epoch-minus-monotonic range for one bracketed read. Bracket width alone omits
clock resolution, drift, possible wall-clock steps and external timestamp-source
uncertainty. It does not establish that PCAP shares the same clock.

The [PCAP format](https://datatracker.ietf.org/doc/draft-ietf-opsawg-pcap/)
defines epoch timestamp fields and file resolution; that does not verify the
installed writer's actual source or capture point. The Mac collector must retain
source/version evidence for the owned netsim capture's timestamp origin,
resolution and same-host relation, together with all per-request clock brackets.
The fixture deliberately leaves `captureClockSourceVerified=false`. A matching
calendar date, one unbracketed host load sample or similar interval medians are
not sufficient calibration. Do not retroactively align F1/F2 from such samples.

Only after timestamp source and uncertainty are defensible may a separate
analysis compare per-connection **same-host** epoch bounds around request capture,
host parsing/header write and response capture. Keep sampling widths, resolution,
offset spread/steps and ambiguous/negative ordering explicit; overlapping bounds
leave that boundary unresolved. Otherwise report each clock separately and leave
mapping unverified. Never subtract guest and host timestamps, fabricate a paired
signal return, equate queued writes with kernel transmission, or clear existing
trace/packet quality failures. This diagnostic is meant to distinguish the
unobserved pre-host-read and post-host-write portions before any performance rerun.
All real presentation and physical three-image/native/GPU gaps remain open.

### One nonce at a host socket boundary

Expert's static audit of the installed SHA `7154f4b8…55457a` found export-trie
addresses beyond the earlier nlist table. `CapturedSink::start_send` takes time
at `0x1006d0ef0`, before capture enqueue and its internal forwarding call;
`CapturedStream::poll_next` reads its internal stream at `0x1006d0fe4`, then
takes time on the successful branch at `0x1006d101c`. Both call
`SystemTime::now` through `clock_gettime(0)`. The inspected Wi-Fi/PCAP writer
path preserves that input timestamp and truncates nanoseconds to microseconds
(less than 1 microsecond loss per record). This does not bound queue residence,
dispatch, clock steps, or the full clock relationship. In particular, a writer
flush interval is not automatically added to a previously captured timestamp.
The exact build-to-source pin and per-packet dynamic dispatch remain unverified.

The next tool is explicitly an **alternative, changed diagnostic path**:
`--single-socket-boundary`. It forwards one exact synthetic nonce through an
owned Python relay to an owned loopback fixture. It records real `recv`/`send`
**Python return points on the relay's sockets**, with FD/port identities,
byte offsets, partial writes, sequence numbers, thread/PID, and nearby bracketed
epoch reads. These are not netsimd's syscall returns or kernel send/receive
timestamps. No SDK process is attached, patched or replaced. No production
transport or network settings change. Do not compare its latency with the direct
F1/F2/F3 path as a speed improvement.

The fixture and relay share the same Python process and `time.monotonic_ns`
implementation/resolution; the offline checker verifies those identities before
reporting two signed local deltas: complete relay request read to fixture parsed
headers, and fixture header-write return record to relay first response read
record. A negative value may reflect overlapping userspace return records; it
is retained. No PCAP/host or guest/host timestamp subtraction occurs. The three
capture-clock validation flags stay false, as do UI/physical-memory acceptance
and actual netsimd-socket observation. Old samples and route-9's strict failure
are never reinterpreted by this mode.

Before the nonce, `host-probe-preflight.json` records an actual attempt to enable
four DTrace syscall-return probes under existing privileges. The program has an
always-excluding userspace PID predicate and exits from BEGIN; it neither
attaches to netsimd nor records traffic. No sudo, image, SDK or permission change
is attempted. The receipt preserves command, exit and diagnostic output. Missing
executable, rejection, timeout and successful activation are distinct. Activation
alone still does not supply the missing netsimd FD/nonce/dynamic-dispatch
collector. This mode deliberately selects the relay alternative regardless of
activation status and never silently claims full netsimd instrumentation.
The [DTrace probe syntax reference](https://docs.oracle.com/cd/E18752_01/html/819-5488/gcfar.html)
describes provider/module/function/name; actual Mac provider availability is a
local preflight result, not inferred from this reference.

A standalone preflight needs no emulator (a nonzero exit is retained evidence,
not an instruction to obtain privileges):

```sh
python3 tools/reader-cache/host_probe.py --output build/reader-host-probe-fresh.json
```

Windows reports `unsupported_platform`/exit 1 and cannot establish Mac rights.
On this Windows Python 3.12, the real boundary clock was `GetTickCount64()` with
15.625 ms resolution. It can quantize short deltas to zero. Recording overhead
is therefore measured separately using **perf_counter duration spans**, with its
own implementation/resolution in the receipt (observed Windows QPC resolution
100 ns). No absolute perf-counter timestamp is subtracted from a monotonic one.
Each I/O record includes an observed local recording cost, and the checker
rejects a missing cost, inadequate cost-clock resolution or a maximum above
`--socket-recording-budget-us` (default 1000). This sampled interval excludes
some wrapper/tail and scheduling effects; it is not a complete overhead or
clock-error bound. A passed budget is not a latency acceptance result.

After rebuilding the isolated test APK, the Mac collector should run **one**
foreground heavy/emulator session, using its existing original-config owner and
netsim Wi-Fi capture procedure, with this runner invocation:

```sh
python3 tools/reader-cache/run_observed.py \
  --platform android --suite route --transport-mode legacy \
  --transport-observer none --single-socket-boundary \
  --host "$EXISTING_CONFIGURED_IPV4" --serial "$OWNED_EMULATOR_SERIAL" \
  --adb "$ANDROID_SDK_ROOT/platform-tools/adb" \
  --aapt2 "$EXISTING_AAPT2" \
  --apk androidApp/build/outputs/apk/debug/androidApp-debug.apk \
  --test-apk androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk \
  --output build/reader-observed/android-one-boundary-fresh
```

These variables refer to the collector's existing configuration/owned serial;
never copy account settings or real materials into the result. Keep API35 Pixel
Fold, 2208x1840/420dpi/fontScale1.0, PDF/Poppler and SDK binary receipts. The new
test APK is required: a stale report without `boundaryControl=single-relay`
fails. This command sends one `/health` control and exactly one configured-route
nonce, no 16-request ABBA loop, CIO, UI, memory sampler or FrameTimeline. Optional
existing scheduling/syscall flags can collect a separate guest trace but do not
make its clock compatible with the host. Do not add them without a specific
missing boundary. No performance/behavior/real-service suite is requested.

The relay permits only health and an exact authenticated synthetic page-1 nonce,
preserves HTTP bytes, and rejects other or repeated requests before forwarding.
The checker joins the app echo/body hash, both relay socket-port identities and
fixture connection/ordinal; it fails missing/duplicate/dropped/errored events,
incomplete byte coverage, invalid timing, failed observed recording budget or
unjoined/forced cleanup. Save failed artifacts. Foreground completion joins all
owned relay/fixture work. After the owned AVD/daemon exits and the radio writer
closes, the existing offline filter supports the single mode too, with only its
exact configured host/relay port entering shared output. Filtering is not yet
per-packet nonce/dynamic-dispatch verification; keep original radio traffic local.

Replay the exact single run offline:

```sh
python3 tools/reader-cache/host_socket_boundary.py \
  --run-dir build/reader-observed/android-one-boundary-fresh
```

Windows synthetic checks use invented PNG bytes and real local TCP sockets,
including partial-send/error and missing-event regressions. Runner tests use
fake Android/SDK commands and real host sockets; they do not count as native
compile or measurement evidence. The self-check output is labeled
`platform=synthetic-host-check,nativeExecuted=false` under
`build/reader-single-boundary-check/<runId>/`, and can be replayed with the same
offline command. Exact source head, preflight status, one nonce, event counts,
clock resolutions, observed overhead and all retained gaps must accompany any
native handoff. None of these tools establishes the 100 ms actual-presentation
target or the three-image physical/native/GPU budget.

### Port observation domains after the first native single probe

The first native single run at `5e08f585` retained a strict failure: the app
reported local port 36994 while the host relay observed peer port 58236. The
same-host relay outgoing port and fixture client port both read 58237. One
nonce/echo/body hash and seven I/O records matched, but these records did not
supply a guest-to-host port translation witness. DTrace activation was rejected
under the current Mac privileges. No new native run is justified by this alone.

The previous cross-domain number-equality assumption was not a valid general
identity contract. Emulator networking is version-dependent; see the official
[network address space](https://developer.android.com/studio/run/emulator-networking-address)
and [interconnection](https://developer.android.com/studio/run/emulator-networking-interconnect)
descriptions. These documents are context, not proof of the actual translation
component or mapping in this run. Do not attribute the delay to NAT from unequal
ports, and do not treat equal port numbers as a verified mapping either.

The checker now adds `identityChecks` and `portObservationDomains`, identifying
the cross-domain numeric comparison separately from the nonce/target and
same-host relay-to-fixture checks. All existing strict conditions, failure names,
recording/byte/cleanup guards, runner exit and filtering gates are preserved.
`crossDomainPortMappingVerified` stays false, including a host-only test where
port numbers happen to agree. This is diagnostic detail, not an exception that
turns the native failure into a pass. Original reports are not overwritten.

A future witness must keep the client/guest flow and host relay flow separate,
tie each to the exact nonce and complete request/response bytes, retain unique
TCP stream candidates and the owned capture's provenance, and distinguish
message-flow linkage from a verified translation mechanism. The existing
native attachment has no filtered packet records, so it cannot supply that
witness. First review a bounded offline extraction/linkage contract for the
already stopped, host-local capture; do not bypass the current filter gate,
transfer the raw radio file, guess a translated port or launch another emulator.
500 ms causation, clock alignment and original acceptance gaps remain open.

### Android test dispatcher and temporary emulator geometry

The product-suite run at `3e03628f` used a Pixel Fold AVD with physical
2208x1840/420dpi but leftover overrides of 1972x1248/320dpi. Those results do not
meet the original geometry contract. The behavior failure was a missing Looper
while creating LazyColumn's Android prefetch scheduler. Its stack passes through
the test frame clock and applying-continuation interceptor on a Default worker.
It does not establish a production-reader failure.

The three Android reader product suites now use the existing Compose 1.12.1
`androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` entry. Installed
bytecode confirms this selects the standard composition dispatcher rather than
v1's unconfined dispatcher. No Compose/dependency upgrade, reader scheduling
change, Looper preparation on a worker, or assertion removal is involved. See
the official [v2 migration guide](https://developer.android.com/develop/ui/compose/testing/migrate-v2).
This is a test-driver fix candidate; the unchanged behavior test must pass
natively before the exception is considered resolved. New timings also reflect
a different test driver, so do not pool them with v1 measurements.

For a new native run, the existing geometry contract authorizes only a temporary
change inside the **owned emulator guest**, while holding the existing emulator
slot. Confirm its explicit serial, qemu identity and AVD ownership; save size,
density, font scale and posture first. On that serial only, `wm size reset` and
`wm density reset` remove the guest overrides. Verify 2208x1840/420dpi with no
override, fontScale 1.0 and the required open posture before measuring. A mismatch
means stop and report the condition, not collect another nonconforming result.

In a `finally` path, restore the saved guest overrides (1972x1248/320 for the
reported session, if still current), verify restoration, then clean up the owned
emulator and isolated packages. Do not reset a physical device, another guest,
or host Windows/macOS settings. Keep both configuration receipts. Old results
and their false flags are not relabeled as conforming measurements.

Trace Processor is not required during capture. The prior Mac engineering
receipts used `.agent_context/trace_processor` with a cached v59.1 binary. Prefer
that already-used native cache for offline SQL; inspect the wrapper's pinned
version, checksum and cache path **before** invoking it, since a wrapper may
download on first use. A missing cache must be reported, without an automatic
download or version switch. Keep parsing separate from the emulator slot. Use
the existing `window_frames.sql` with the run's measurement PID, retain quality
and all dropped/ambiguous candidates, and keep wrong-geometry traces excluded
from contractual latency/physical-memory acceptance. No route/radio work is
required for this product verification.

### Backing high water at the conforming `01d8b2b` run

Independent replay of QA-C's synthetic Android performance trace identifies all
five backings at its first maximum, event 77 at 2,288,779,626 process-local ns.
These are the first five allocations, all decoded 1200x1600/7,680,000-byte
bitmaps, with no scratch allocation: image/page 3/1, 6/2, 8/3, 11/4 and 14/5.
Images 3 and 6 already have composition-detach, cache-eviction and
`release_deferred_renderer` receipts. Image 8 is the previous cached page,
11 is the current composed page, and 14 is the new in-flight prefetch. The next
recorded frame-submitted event observes three weak backings, 37.063 ms after
the first maximum. This bounds only those sampled observations, not the exact
allocation lifetime or renderer/GPU retention interval.

The 38,400,000-byte weak high water is retained as evidence. It includes two
retired displayed pages; it is neither a five-entry cache nor proof that the
physical incremental three-image budget passes. Conversely, comparing that
absolute total with an incremental budget cannot establish the paired delta
without a matching baseline. Do not recycle drawn Android bitmaps based only
on composition disposal or a frame-submission callback, and do not force GC to
make the peak disappear.

New traces keep both high-water counters and identity-preserving snapshots at
the exact same weak-reference sample. Receipts include storage, size, source
session/page, publication/retirement, composed ownership and the lease's actual
logical references before/after clearing. Release disposition is distinct from
`valueCleared`; neither certifies native/GPU release. Snapshot metadata is
bounded by the existing allocation limit and observation remains disabled in
normal execution. Earlier artifacts are not backfilled with these fields.

```sh
python3 tools/reader-cache/backing_lifetimes.py OUTPUT/trace.json \
  --output OUTPUT/backing-lifetimes.json
```

The checker rejects missing peak identities, duplicate identity/byte coverage,
inconsistent lease ownership and missing peak-event witnesses. A complete
report verifies the receipts only. It does not subtract retired pages from the
original peak, claim a physical budget, or treat final-GC liveness as peak data.
Cache eviction is recorded before `lease.retire()`: if another thread allocates
in between, the snapshot reports a pending retirement and still counts that
logical value. It must not mistake the eviction marker for a cleared lease.
Native receipts for this new instrumentation and its effect on measurements
must still be collected and kept separate from `01d8b2b`.

Use the card-specific package `com.coach.readermeasurement.ton1463` for the next
APK build (`-PcoachApplicationId=...`) and runner (`--package ...`). APK package
guards, sampler/run-as, instrumentation and Perfetto app filters all use that
explicit identity. Legacy measurement packages remain selectable for old
artifacts, never silently translated or used with a mismatched test APK. Retain
the existing geometry save/reset/restore and default UI memory sampling; remove
both TON-1463 packages after the owned session.

### Package-gate delivery regression

The first `51789326` TON-1463 APK pair installed successfully, but all three
product suites stopped at a test-only equality check for the legacy package.
Those failed attempts produced no meaningful measurements. The runner's APK
allowlist alone was not an end-to-end package contract.

All four Android reader entries (including transport) now call the same
test-only `ReaderMeasurementPackage.requireIsolated` gate. It accepts exactly
`com.coach.readermeasurement` and `com.coach.readermeasurement.ton1463`; production,
prefix matches and the `.test` instrumentation package are rejected. Other
fixture/port/behavior assertions are unchanged.

After rebuilding both APKs, Windows can execute the **actual guard class from
the Android build**, check both allowed identities and eight rejection cases,
and inspect all compiled entry bindings:

```sh
python tools/reader-cache/check_measurement_package.py --java-home "$JAVA_HOME" \
  --output build/reader-package-gate
```

This host check does not run Android instrumentation, obtain an Android Context,
or prove a native UI entry. `nativeEntryReachedVerified` stays false. The check
never recompiles or substitutes the gate: its classpath starts with the Android
build's class directory; only the small JVM test harness is compiled separately.

On the owned Mac emulator, validate/install the same signed pair, then run the
small delivery preflight before any UI measurement/capture:

```sh
"$ADB" -s "$OWNED_EMULATOR_SERIAL" shell am instrument -w \
  -e class com.coach.android.ReaderMeasurementPackageSmoke \
  com.coach.readermeasurement.ton1463.test/androidx.test.runner.AndroidJUnitRunner
```

Require `OK (1 test)` and no test failure, not just the ADB exit code. This test
checks the installed target's shared gate and the instrumentation/target package
relationship; it opens no UI or fixture. If it fails, retain the log and clean up
without running the three measurement suites. A passing preflight is only an
entry check, after which performance/behavior/lifecycle still need their existing
assertions, native receipts and normal save/reset/restore/cleanup discipline.
