# Authenticated book Practice pose

This extends PR238's `BookPracticeOutbox` with a real server pose. The paired
learner sends a locator, never a question, key, knowledge point, approval or
device identity in the body:

```http
POST /v1/courses/{course}/book-practice-pose
Authorization: Bearer <existing paired credential>
X-Coach-Device: <existing paired device>
Content-Type: application/json

{"bookSha256":"<64 lowercase hex>","lessonId":"<local lesson>","pdfPage":1,"start":0,"end":120,"reference":"<exact source quote>"}
```

The response is `{"events":[<question_posed>]}`. Its original attempt and frozen
question come from the server, `commandId` is null, and `deviceId` is the
authenticated device. `bookPractice` carries the exact locator plus the existing
local block ID (`{sha}:p{page}:{start}`). No answer key is returned. Repeating the
same device/block returns the identical persisted event, including after reopening
SQLite. A changed mapping or voided attempt is refused rather than silently
allocating a different original attempt.

The route reauthorizes inside the single writer. Malformed/extra fields return
400, unauthorized credentials return 401, and unavailable or mismatched source
identity returns 409. Rejections append no learning events.

## Owner import, source permission and reviewed questions

`ContentSeed.bookPracticeMappings` is an explicit owner-import allowlist. Each
entry contains `source` (the six locator fields), `kpId`, `questionId`, and
`questionFingerprint`. It is installed atomically with the existing course seed;
an invalid mapping rolls back metadata and events. An ordinary seed without
mappings removes this course's allowlist. Duplicate block mappings are rejected.
This is not a new paired-client import or approval endpoint.

The existing offline `seed-content` command accepts an optional local JSON array:

```sh
seed-content --snapshot <existing lawful captured snapshot> --database <offline private database> --out <private output> --book-practice-mappings <local mapping array.json>
```

This uses the existing private destination and server lock checks. It reads no
new external source, uploads nothing, and never changes a question to VERIFIED.
The existing structured CLI still supports its existing course snapshot format;
other owner integrations can use the same `LedgerStore.seedContent(ContentSeed)`
contract. This change does not add a general book upload tool.

Import and every pose/answer recheck all of the following:

- The course has exactly one tutor-visible TEXTBOOK document with the requested
  SHA. The server blob exists and its bytes rehash to that SHA.
- The stored physical page text matches its stored hash. The range is in bounds
  and includes the reference.
- The active course KP anchors that document/page. An explicit SOURCE_REFERENCE
  locator is accepted; a quoted anchor must contain the reference and itself be
  present on the stored page. A chapter-range hint alone is insufficient.
- The mapped single-choice question belongs to that course/KP. Its recomputed
  fingerprint and source quote match, options are unambiguous, and the existing
  VERIFIED key and independent generator/reviewer provenance are present.

Missing existing review/permission evidence remains unavailable. A local
definition exercise cannot approve itself or become a ledger question merely
because its text resembles a textbook.

## Original answer and current limits

After normal ledger sync, PR238 binds the exact server pose to the local Practice.
Its outbox sends the learner's actual choice and `guessed` via existing
`submit_answer`, with the original attempt and frozen snapshot. Only a pose in
the server's new registry can authorize a book Practice answer. A foreign device,
assistant/thread scope, changed fingerprint/link, removed mapping or unregistered
attempt is rejected before answer/grade events. The new registry is additive;
there is no retrofit of legacy or fabricated book poses.

The regression uses a synthetic Markdown original whose bytes are the actual
seeded server blob, real paired HTTP, client/server SQLite and restarts. Reviewed
test-bank provenance is synthetic and is not approval of production material.
Both correct and incorrect originals and `guessed=true` survive into PR229's
existing server-facts producer. No internal `approveBookLedgerSource` or direct
pose stands in for the positive HTTP path.

The original PDF SHA can differ from the server's extracted Markdown blob SHA.
This route follows PR229's existing SHA contract and refuses that mismatch; it
does not invent an original-file/page-version association. PDF pagination,
lawful real-material approval and the Workspace/Controller native call site are
separate outstanding integration gates. This implementation does not establish
that the full native review flow is ready.

The existing ordinary `submit_answer` path (without `bookPractice` or exact-review
identity) still lacks general online pose device ownership checks. Follow up on
TON-1448: bind ordinary server poses to their authenticated device before answer
events, while preserving the existing legitimate offline snapshot path. This PR
only guards book Practice attempts and does not widen that fix.
