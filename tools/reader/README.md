Private PDF reader (TON-1400, phase-one foundation)
================================================

The reader has a separate private allowlist and authenticated course-scoped transport. This PR supplies PDF page images/text, exact citation resolution, server-ledger progress/annotations and one-document Markdown export. The existing Resources screen opens a private PDF viewer with single-page and wide two-page display. Page turns save to the ledger; same-version progress is restored on reopening. A failed save can be retried using the same mutation; a conflict retains the current page in this view and offers an explicit choice to load the server position or overwrite it after refreshing the revision. In the extracted page text, select a passage to create a note; edits save automatically, reopening restores saved notes, and conflicts offer an explicit choice between the local and server text. Old-version or mismatched anchors remain visible but cannot be edited or highlighted as current. The text highlight is not a highlight on the rendered PDF. Unsaved progress and note drafts are in memory only and may be lost if the app or view closes before the server acknowledges them. The existing mainline answer explanation links a supported exact source directly to the registered PDF page, with unknown or ambiguous sources reported instead of guessed. A page-only target is not a verified fact or visual highlight. It does **not** complete phase one: other source entry points, shared reading appearance and stars, actual PDF highlights, course/fact/question integration, selected-text AI, chapter map and external device/performance gates remain to be implemented. EPUB is outside this phase.

Local setup
-----------

Requires `pdfinfo`, `pdftoppm` and `pdftotext` (Poppler) in the **server process PATH**. No package installation or production restart is performed by the importer. The server starts normally with an empty catalog when `COACH_READER_LIBRARY` is unset.

Explicitly select each permitted textbook, slide deck or required reading. Never recursively import a course directory: tutorial keys and sealed material must stay out. Run outside production:

```sh
python3 tools/reader/import_pdf.py /private/path/to/selected.pdf \
  --library ~/Coach/reader-development --id textbook \
  --course ACTUAL_LEDGER_COURSE_ID --title 'Course textbook' \
  --kind textbook --printed-page-offset 31
```

For lecture slides use `--kind slides --week N` and omit the offset unless explicitly verified; required readings use `--kind reading`. The importer refuses a library inside a Git checkout and requires directory mode 0700. It copies one file to `books/<sha256>.pdf`, validates the copied PDF with Poppler and atomically updates `catalog.json` under an importer lock. Files are mode 0600. It never changes an application environment or starts/restarts a service. Configure `COACH_READER_LIBRARY` only on the isolated development server; Root owns production.

Catalog schema 1 contains `documents`: each entry has `id`, actual ledger `course`, `title`, relative `file`, `sha256`, `pages`, `kind`, optional `printedPageOffset` and `week`. Files must resolve under the catalog root; duplicate `(course,id)` entries fail startup. Updates require a server restart. Old content-addressed files/caches are retained deliberately; remove them only after retaining the versions needed for old annotations. No catalog, book, extracted text, page image, note export, credential or private test artifact belongs in Git or an issue attachment.

Local-book explanation registration (TON-1466)
--------------------------------------------

The Windows local reader identifies a book by the SHA-256 of its complete file. To let its existing selected-text explanation use an **already registered learner source**, the server operator can explicitly bind that exact PDF to the source's `docId`:

```sh
python3 tools/reader/import_pdf.py /private/path/to/selected.pdf \
  --library ~/Coach/reader-development --id textbook \
  --course ACTUAL_LEDGER_COURSE_ID --title 'Course textbook' \
  --kind textbook --explain-source-id EXISTING_LEARNER_SOURCE_DOC_ID
```

This adds optional `explainSourceId` to the private catalog. The ledger source must already have the same course and physical **1-based PDF page numbers**, trusted page text, learner/tutor visibility and an existing learned knowledge-point anchor. The printed-page offset does not translate these anchors. The importer does not create source pages, learning history or permissions, or infer a mapping from a title. No binding is added without this flag; reimporting the same `(course,id)` without it revokes the binding. A hash with multiple opted-in entries is ambiguous and rejected, even across courses.

`/v1/library/explain` uses this server-owned binding for local hash IDs, then checks the selected quote against the stored source and learned scope. Client course, visibility and source claims confer no authority. Only the selection and one local navigation anchor reach the existing explanation path; neighbouring blocks are discarded. The server re-reads the catalog and hashes the file before and after generation, so revocation, file changes or invalid catalogs fail closed without a restart for this endpoint. Reader catalog/page views still reload at restart. A book without a binding, an unread page or a protected source is refused; TXT/EPUB binding is not provided by this PDF importer.

The existing desktop per-book explanation consent still applies. This path sends the authorized selection through the configured tutor bridge, which can invoke external subscription CLIs; it does not establish on-device inference. A catalog registration alone does not call AI or export text. Keep real-material and production acceptance on the controlled acceptance lane.

Saved desktop pages, including OCR and common formats (TON-1663)
---------------------------------------------------------------

For an already imported local book, use its **saved desktop pages** instead of extracting its original again. This supports all currently stored formats, including PNG/JPG, scanned PDF, TXT/Markdown, Word, PowerPoint, HTML, Kindle, RTF, ODT and EPUB. Explicitly select one book ID from the desktop library; do not pass an entire course directory:

```sh
python3 tools/reader/import_local_book.py \
  --desktop-library /private/path/to/selected-desktop-library \
  --book SHA256_OF_ONE_IMPORTED_BOOK \
  --library ~/Coach/reader-development --id selected-book \
  --course ACTUAL_LEDGER_COURSE_ID \
  --explain-source-id EXISTING_LEARNER_SOURCE_DOC_ID
```

This operator-side tool reads that book's `books`/`pages` records in a read-only SQLite transaction, copies only its original, and saves an immutable page snapshot plus `local-books.json`. Transfer only the explicitly selected import when desktop and server are different machines. Original SHA-256, exact page numbers, Unicode text and OCR flags are retained. It does not OCR again, normalize whitespace, insert illustrations into text, or alter UTF-16 offsets. Original and snapshot files are private and content-addressed; the independent PDF catalog is preserved.

**The existing trusted ledger source is still required.** Its selected page text must equal the saved import page exactly, including whitespace; it needs learner/tutor visibility and a learned outline anchor in the bound course. Registering a book does not create that source or grant learning permissions. This is an operator setup tool, not automatic drag-and-drop enrollment or an HTTP upload route. On this operator-binding path, a new arbitrary local book returns 409 until its source and binding have been explicitly established. The separate personal-import receipt path below provides automatic registration for newly imported books. Existing per-book desktop consent applies before an explanation request.

The explanation endpoint checks the saved block at the block ID's UTF-16 start, the trusted page and learned scope, and hashes the original and page snapshot before/after generation. Revocation, ambiguous bindings (including a simultaneous PDF binding), mismatched text or changed bytes fail closed. Remove the selected entry from `local-books.json` to revoke it; the explanation endpoint reloads it without restarting. Do not bind the same hash through both importers.

Self-authored real-subscription reproduction (no production service or user library):

```sh
tools/dev/heavy ./gradlew :desktopApp:test --tests SavedBookBindingTest
tools/dev/heavy ./gradlew :server:app:installDist
tools/dev/heavy python3 tools/reader/check_local_book_live.py \
  --bridge-python /path/to/coach-bridge-venv/bin/python \
  --output /private/new-evidence-directory
```

The Windows test exports only committed self-authored fixtures to `desktopApp/build/screens/saved-book-binding`; the check verifies their original hashes against those fixtures, seeds disposable trusted sources, and uses new loopback ports and private bridge state. It uses the existing logged-in subscription CLI without changing accounts. Its report distinguishes the actual model calls from native-window and production acceptance. See `docs/acceptance/TON-1663-source-binding.md`.

First explanation of a personal import (TON-1663)
-----------------------------------------------

New desktop imports now use a separate, device-owned source registration on the first **consented** explanation request. No operator import, course event, source-document grant or learned outline anchor is needed for that personal book. This does not change the catalog-bound course-source path above: a hash present in either private catalog must still pass its existing trusted source and learned-scope checks. Unreadable catalogs fail closed. Requests without a personal-import receipt retain the previous behavior.

The desktop checks its original file's SHA-256 and each submitted block's exact saved page/UTF-16 range before sending and after receiving. The `localSource` receipt contains schema 1, book hash, format, title, consent and page metadata (`number`, UTF-16 `length`, `ocr`, SHA-256 of the UTF-8 saved text). It contains no original file or complete page text. Only the already consented selection/context blocks accompany it; only the selected quote reaches the model. The server stores these submitted fragments and immutable page identities privately in `COACH_DATA_DIR/personal-imports/sources.sqlite`, partitioned by the authenticated device. A client receipt is a claim about that device's personal import, not server verification of the original bytes or authority over a course source.

Conflicting page identities, overlapping text changes, invalid bounds and oversized requests are refused before model invocation. The endpoint rechecks registration and catalog ownership after generation; removal/change during generation withholds the answer. There is a 128 KiB request limit and 25,000 stored-fragment limit per device. Registered fragments persist until an operator removes them from the private SQLite database; removing a desktop book or its consent marker does not erase server copies already sent. Removing the consent marker prevents further requests from that desktop. This registration does not upload the book to the shared reader catalog or search index.

Reproduce automatic registration with the same self-authored exports and an isolated service:

```sh
tools/dev/heavy ./gradlew :server:app:installDist
tools/dev/heavy python3 tools/reader/check_local_book_live.py --automatic \
  --bridge-python /path/to/coach-bridge-venv/bin/python \
  --output /private/new-automatic-evidence-directory
```

Add `--native-windows` to exercise real Windows file-drop, per-book consent, subscription questions and exact source navigation in both themes. This uses the normal paired HTTP client with disposable credentials and an SSH loopback forward to the isolated service. Its run-owned connection file is outside the checkout and deleted afterwards. The script refuses to count a Mac fallback as Windows evidence. It never changes the production service, Windows pairing, subscription accounts or a user library. The service must be reviewed and updated separately before existing paired installations gain this behavior.

Transport contract for TON-1394 integration
-----------------------------------------

Reuse `CourseHttp.reader` in the existing paired course client. It shares auth, local-network validation, cancellation, timeout and no-redirect behavior. ResourcesScreen adds an entry without replacing existing source navigation. MainlineScreen adds a source button at the existing answer-explanation label; coordinate this small overlapping integration with PR #117 before combining the branches. CoachApp remains untouched. Reuse its common reading appearance, selection menu and emphasis stars when available; do not introduce a second set of those components.

All paths below are relative to `/v1/courses/{course}/reader`, use existing `Authorization: Bearer …` and `X-Coach-Device` headers and return `Cache-Control: no-store`. Credentials in query parameters are rejected. There is no raw-file route, client-supplied filesystem path or AI route.

| Method / path | Result |
| --- | --- |
| GET root | `documents` metadata, `privateAi=disabled_no_local_inference` |
| GET `/resolve?source=…` | Exact `教材 p.N` or `Wn 课件 p.N` → `documentId,version,pdfPage,printedPage,anchorStatus=page_only`; 422 for unknown/ambiguous/out of range |
| GET `/{id}/pages/{page}` | `documentId,version,pdfPage,printedPage,text,textSha256,privateAi` |
| GET `/{id}/pages/{page}/image?version=…` | Actual local PDF rendered to PNG, longest edge 1600 px, disk cache by content hash/page/render size; optional expected version returns 409 on mismatch |
| GET `/{id}/state` | `document`, `progress` or null, latest nondeleted `notes`, each with ledger `revision` and `stale` |
| POST `/{id}/progress` | Versioned page progress, returns `revision,savedAt,replayed` |
| POST `/{id}/notes` | Highlight and Markdown note, returns `revision,savedAt,replayed` |
| GET `/{id}/notes.md` | One-document Markdown attachment with PDF/printed pages, quotes, notes and stable annotation identifiers |

PDF pages are **1-based**. Printed page is `pdfPage - printedPageOffset`; front matter at or below zero has null printed page. This mapping does not prove the fact is on that page, and `page_only` must not be presented as a verified quote/highlight. Existing chapter-text `docId` values have no guessed alias to a PDF. Register an explicit mapping when integrating old source jumps. Catalogs can contain several textbooks, but shorthand citations resolve only if exactly one applicable document exists.

Progress body: `{version,pdfPage,baseRevision,mutationId}`. Note body: `{id,version,pdfPage,textSha256,start,end,quote,note,deleted,baseRevision,mutationId}`. IDs/mutation IDs match `[a-zA-Z0-9_-]{1,80}`. `start/end` are UTF-16 indices into the exact returned page `text`, end exclusive, quote at most 4000 UTF-16 units, Markdown note at most 8000. Body limit is 32768 bytes, including streamed requests. Rendered image coordinates are not text offsets: PDF highlight geometry still needs a local text-box mapping; do not invent coordinates from character counts.

New progress/note uses `baseRevision=0`; updates/deletes use the last acknowledged revision **of that progress or that note**, not global ledger sequence. Reuse the same immutable body and mutation ID for a failed transport retry. Same device/mutation replay returns the original revision across server restarts; reuse for another document, operation or body is 409. Device races and stale revisions are 409; keep the user's local draft and offer reload/merge rather than silently retrying with a new revision. A note's anchor cannot be moved by updating it; create a new note. Deletes are ledger tombstones. Rendering occurs off the ledger actor; writes and compare-and-swap are serialized through the existing actor.

On document replacement old annotations/progress remain visible with `stale=true`; never automatically navigate/highlight them against the new version. The Markdown export labels old-version anchors and omits a derived new printed-page number for them. It downloads through the paired client; integration must let Root choose the knowledge-base destination, never write an arbitrary server path.

Privacy audit
-------------

Existing `ChatService` prepares and renders a `SourcePack` containing page extracts, then passes it to `BridgeClient` (`system`/`user` messages). `tools/bridge/subscription_bridge.py` renders those messages into prompts and invokes subscription CLI providers (`call_claude`, `call_codex`, `call_agy`). A loopback bridge and read sandbox therefore do not establish on-Mac inference. Sending private PDF selections through that path would cross this issue's no-third-party boundary.

The catalog/page routes do not import pages into the tutor's source index or invoke the bridge; their metadata report AI disabled. The separate local-book explanation endpoint above requires the desktop's existing per-book consent and either an explicit server binding or a device-owned personal-import receipt. Its authorized selections use the configured tutor bridge and can leave the machine. No opt-in environment variable silently enables catalog-wide AI. Do not run private-source live AI tests on the implementation lane. The requested 30-answer citation gate remains unverified.

Verification
------------

```sh
tools/dev/heavy ./gradlew :server:app:test --tests com.coach.server.ReaderTest
tools/dev/heavy ./gradlew :shared:ui:jvmTest --tests com.coach.ui.PrivateReaderScreenTest --tests com.coach.ui.PrivateReaderIntegrationTest
python3 -m unittest discover -s tools/reader -p 'test_*.py'
```

Tests generate invented PDFs locally, exercise actual Poppler images/text through the paired client, auth/course isolation, exact 31-page offset, restart persistence, idempotent retries, simultaneous-device conflicts, deletion/export, forged/stale anchors and allowlist containment. The Compose integration test runs against an isolated server and two synthetic courses, including a conflicting edit, a server restart and a replaced document. They do not constitute the 50-fact/30-answer external sample, performance benchmarks, Fold8-size layout checks, emulator, OPPO or Root production acceptance.
