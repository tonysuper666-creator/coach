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

# Native, interactive window; close it to dispose its server and temporary files.
tools/dev/heavy --local ./gradlew :desktopApp:citationWindowSmoke

# Owned interactive desktop with an English input method: AWT mouse/keyboard replay.
# The harness does not change the host input method; Chinese IME composition is
# not an ASCII typing replay. A timeout fails the task and captures its own window.
# On Windows, point coach.pdf.bin at the existing Poppler bin if it is not in PATH.
./gradlew :desktopApp:citationWindowSmoke -Pcoach.citation.auto=true \
  -Pcoach.autoExitSeconds=90
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
first draw of the ready reader subtree, including HTTP/PDF work. It is one
sample, not proof that all real clicks meet 300 ms, and does not measure monitor
scanout. Offscreen Compose results do not count as native Windows clicks.
The 50 textbook cases, Windows acceptance, performance threshold and final
independent integration/Architect review remain separate gates.

## Candidate validation and remaining native gap

The Mac run passed the five new HTTP/navigation tests. The existing reader
regression selection passed 46 tests before the additional changed-PDF HTTP
regression was added; that additional test then passed in the five-test run.
These results are not native Windows acceptance.

The Windows synthetic native entry started successfully. Two author mouse/key
replays stopped before requesting the explanation because the active Chinese
IME composed the scripted ASCII draft. The second replay records choice 1,
draft length 21 rather than the expected 25, and exits 1. No source-click,
return, or native drawing-time result was obtained. The first harness revision
incorrectly inherited Compose's process-exit success despite its printed failure;
this candidate disables that implicit exit and fails on exceptions or timeout.
The harness leaves input-method configuration alone. Independently repeat with
English input in its owned window, or use the same entry manually, before
counting Windows click/return or the 300 ms gate as verified.
