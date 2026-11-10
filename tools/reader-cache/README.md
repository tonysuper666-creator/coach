# Reader page cache measurements

Baseline production source: `1014caf0f57d89bf7b937e8eaa8cd1236675c677`.
At that revision, `PdfPageImage` requests and decodes on every page change.
This measurement commit does not change that code. PR131's isolated Reader2
layout and PR134's desktop window remain separate work.

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

## Current status

This is baseline preparation. The cache is not implemented, the native test
has not been compiled or run on Windows, and no performance or memory target
is verified. After Mac baseline collection, Builder-W continues on the same
PR with the three-page decoded cache and its behavior tests. Independent
acceptance and cross-platform review follow implementation.
