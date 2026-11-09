Private PDF reader (TON-1400, phase-one foundation)
================================================

The reader has a separate private allowlist and authenticated course-scoped transport. This PR supplies PDF page images/text, exact citation resolution, server-ledger progress/annotations and one-document Markdown export. The existing Resources screen now opens a private PDF viewer with single-page and wide two-page display. It restores previously recorded, same-version progress, but page turns in this viewer are **not yet saved** and the UI says so. It does **not** complete phase one: source-click integration, progress writes/conflict handling, shared reading appearance and stars, annotations, course/fact/question integration, selection UI, chapter map and external device/performance gates remain to be implemented. EPUB is outside this phase.

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

Transport contract for TON-1394 integration
-----------------------------------------

Reuse `CourseHttp.reader` in the existing paired course client. It shares auth, local-network validation, cancellation, timeout and no-redirect behavior. MainlineScreen and CoachApp remain untouched, so PR #117 can continue independently; ResourcesScreen adds an entry without replacing existing source navigation. Reuse its common reading appearance, selection menu and emphasis stars when available; do not introduce a second set of those components.

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

This reader does not import pages into the tutor's source index, construct a SourcePack, expose an ask endpoint or invoke the bridge. Its catalog/page metadata explicitly report AI disabled. No opt-in environment variable can silently enable it. Selection questions require an approved inference path that keeps material local, or a new explicit content-export authorization; none is presumed here. Existing unrelated tutor behavior is unchanged. Do not run private-source live AI tests for this issue. The requested 30-answer citation gate remains unverified, not passed by disabling the feature.

Verification
------------

```sh
tools/dev/heavy ./gradlew :server:app:test --tests com.coach.server.ReaderTest
python3 -m unittest discover -s tools/reader -p 'test_*.py'
```

Tests generate invented PDFs locally, exercise actual Poppler images/text through the paired client, auth/course isolation, exact 31-page offset, restart persistence, idempotent retries, simultaneous-device conflicts, deletion/export, forged/stale anchors and allowlist containment. They do not constitute the 50-fact/30-answer external sample, performance benchmarks, Fold8-size layout checks, emulator, OPPO or Root production acceptance.
