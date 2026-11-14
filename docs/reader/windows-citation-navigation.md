# Desktop explanation to native source window

TON-1465's desktop increment is based on main `16679147` plus the unmerged
PR203 `ba08d43c` (including PR142 `b3f1d352` and frozen PR148 `54e6d8ea`).
Its own changes are in `desktopApp` and this document. It must remain draft
until those dependencies and the remaining acceptance gates are resolved.

## Product entry and return contract

`ReaderWindowHost` accepts an explicit course/week/item owner. It loads that
question with `CourseHttp.mainlineWeek`, obtains an explanation with
`explainSelection`, parses its sources as `ExplanationCitation`, then opens
the existing `ReaderWindow` through a restricted `ReaderSource` adapter.
`Main.kt`, `CoachApp`, `AppNavigation` and `TextbookWorkspace` are unchanged.
This is an explicit native reader entry, not a new route in the main navigation.

Configure only an owned, isolated service. Keep credentials out of Gradle
arguments and shell history by supplying `COACH_TOKEN_FILE` through the environment:

```sh
COACH_URL=http://127.0.0.1:YOUR_ISOLATED_PORT \
COACH_COURSE=SYNTHETIC COACH_DEVICE_ID=YOUR_ISOLATED_DEVICE \
COACH_TOKEN_FILE=YOUR_PRIVATE_TOKEN_FILE \
tools/dev/heavy --local ./gradlew :desktopApp:runReaderWindow \
  -Pcoach.reader.week=1 -Pcoach.reader.item=fruit1
```

The question owner, selected option and unsubmitted follow-up draft belong to
`DesktopExplanationSession`, outside the reader's document state. Returning
from the source window preserves them. This entry does not submit the answer.
Retention covers this open window's lifetime; it is not restart persistence.

## Identity and authorization boundary

- The HTTP source must contain a document ID, a 64-digit text version, a positive
  physical PDF page and readable text. A block also requires its block ID,
  PDF SHA256 and valid UTF-16 text range. Missing or mixed identities fail visibly.
- For a block, the catalog ID must match exactly, the PDF digest must match, and
  the page must exist. The fetched page must repeat the same document/version/page.
  The authorized quotation must occur exactly once after whitespace normalization;
  case and punctuation are preserved. Import offsets are not used as PDF offsets.
- Different import and catalog IDs are deliberately rejected. This increment does
  not invent a mapping between them or treat similar titles or text hashes as PDF
  identity. A separately verified source binding is needed for such a document.
- Whole-page fallback displays the server's authorized text snapshot with its
  text version. It does not invent a PDF digest or borrow a catalog page image.
- The reader adapter exposes only the authorized physical page. Neighbor pages,
  other documents, note writes and progress writes are denied. Block display is
  read-only and does not enter the existing IME or source-practice write flows.

## Synthetic replay

No materials, paired user configuration, production traffic or model calls are
needed. The fixture generates a three-page PDF and a disposable database, starts
real product mainline and reader HTTP routes on loopback ephemeral ports, and
uses a deterministic loopback SSE bridge. Layout is an injected synthetic fixture;
this does not verify the private on-disk source-binding pipeline.

```sh
tools/dev/heavy --local ./gradlew :desktopApp:test \
  --tests 'com.coach.desktop.reader.ReaderExplanationNavigationTest' \
  --tests 'com.coach.desktop.reader.ReaderRemoteWindowTest' \
  --tests 'com.coach.desktop.reader.ReaderHttpSourceTest' \
  --tests 'com.coach.desktop.reader.ReaderRemoteTest' \
  --tests 'com.coach.desktop.reader.ReaderWindowTest'

# Mac only: own disposable source sandbox + synthetic SSE bridge. Leave foreground
# running during the native client replay, then create CONTROL/stop to close it.
tools/dev/heavy --local ./gradlew :desktopApp:citationFixtureServer \
  -Pcoach.citation.bind=0.0.0.0 -Pcoach.citation.control=FRESH_PRIVATE_CONTROL_DIR

# Read ready.properties for its ephemeral port. On the isolated Windows runner,
# use a private client.properties file with the existing host environment keys:
# COACH_URL=http://MAC_REACHABLE_ADDRESS:EPHEMERAL_PORT
# COACH_COURSE=SYNTHETIC
# COACH_DEVICE_ID=phone
# COACH_TOKEN_FILE=PRIVATE_SYNTHETIC_TOKEN_FILE
# COACH_READER_ITEM=fruit1
# COACH_READER_WEEK=1
# Token file contains only the fixture credential synthetic-private-token-32-characters.
# No production pairing/configuration is read. The replay rejects other courses.
# Run through the current isolated interactive Windows runner (console 1), whose
# task/arguments are the following; do not run a Mac-only sandbox inside Windows:
./gradlew :desktopApp:citationWindowSmoke -Pcoach.citation.auto=true \
  -Pcoach.citation.samples=20 -Pcoach.autoExitSeconds=180 \
  -Pcoach.citation.environment=PRIVATE_CLIENT_PROPERTIES_FILE
```

The native replay selects an option, types an unsubmitted draft, requests the
real HTTP explanation, clicks its source, checks physical page 1 and the block
selector in the existing reader, then clicks back and checks the same owner,
choice and draft. It captures only its synthetic window under
`desktopApp/build/screens`. An exception produces a nonzero exit.

The new regression class also covers trusted whole-page fallback, wrong book,
wrong PDF version, invalid page bounds, duplicate quotation, missing identity,
unavailable page and neighboring-page/write denial. Before this increment the
desktop reader host had no explanation-source consumer; server JSON alone did
not exercise this path.

`clickToDrawMs` measures the source handler's monotonic timestamp through the
first draw of the ready reader subtree, including HTTP/PDF work. The replay records every sample and the nearest-rank p95, including the first
cold click. This does not measure monitor scanout or establish performance for
real textbooks, networks or other Windows machines. Offscreen Compose results do not count as native Windows clicks.
The 50 textbook cases, Windows acceptance, performance threshold and final
independent integration/Architect review remain separate gates.

## Split-fixture regression

The native harness requires explicit remote configuration and uses the same
`explanationConnection` function as `ReaderWindowHost`. It never starts a server
in the Windows process. The service's existing Mac source sandbox is unchanged.
The Mac fixture binds externally only when explicitly requested; its deterministic
bridge stays on loopback. `ready.properties` and a stop file live outside Git;
close the fixture after the replay and collect its bridge request count.

The Robot replay handles Shift-English only in its own synthetic draft field,
with one bounded retry if English was already selected. It leaves the product
IME implementation unchanged. It captures the synthetic client area and records
source-to-ready-content draw times for repeated real mouse clicks, checking the
owner, option and unsubmitted draft after every return. Errors and incomplete
replays exit nonzero.

The shared host connection regression exercises explicit URL/device/token-file
configuration against real product HTTP routes, without starting any server in
the client connection factory. Mac targeted validation: 48 tests, zero failures,
errors or skips. Native Windows evidence is recorded separately in the delivery
receipt; Mac/offscreen tests alone are not Windows acceptance.

## Author native replay receipt (2026-11-14)

Mac hosted the synthetic product HTTP service and deterministic bridge; the
Windows client connected over the private test network. The current isolated
runner used interactive session 1, slot 0, with source snapshot
`62d4919ebbaa869ac5c97c6e8c5bab0fed8afd0e`. Its job
`heavy-3e9bd266039846908111ac54baf4562d` exited 0. The desktop sources in that
snapshot match this candidate; this receipt was added afterwards.

All 20 real mouse source clicks opened physical page 1 with the expected block
highlight. Every return retained the same owner, selected option and exact
25-character unsubmitted draft. Samples in milliseconds:
`222,166,89,94,86,86,85,86,85,85,84,84,85,87,210,104,198,177,86,162`.
Nearest-rank p95 was 210 ms; maximum was 222 ms, including the first cold click.
The receipt and three synthetic client-area screenshots accompany the issue
delivery. The Mac fixture then closed successfully (exit 0); its two bridge
requests comprise one earlier harness attempt and the completed replay. The
earlier attempt exposed a harness wait/cast race, fixed by waiting for ready
content and the highlight before inspecting the page.

This is author evidence for this synthetic block-source path, pending independent
acceptance. Native whole-page fallback timing, textbook accuracy, other network
conditions and final integration/Architect gates are not established by it.
