# Local book ↔ course ledger identity audit (TON-1448)

Audited local-registration baseline: PR218 `c51d35de511671fc0275ae3d307107a075afee2a`.
Ledger/content contracts also checked against fetched main
`bc88b3a08ebf43029b8c7de8a7be2047df31efbf`; the cited ledger, content seed,
fingerprint and course HTTP implementations are identical at these revisions.
This is an audit and a proposed boundary contract, not an implemented bridge.
No production registration, selector, schedule, reader or server code changes.

## Existing facts and missing joins

| Local fact | Ledger fact | What can actually be established |
| --- | --- | --- |
| `TextbookLibrary.importBook`: SHA-256 of immutable imported file bytes | `CourseContentStore.install`: `source_docs.blob_sha256` hashes `TextbookDocument.markdown.toByteArray()` | Byte equality only if the exact same bytes were imported. No original-file SHA mapping is supplied by `ContentSeed`; PDF/EPUB hashes cannot be substituted with Markdown hashes. `/sources` does not expose the blob hash. |
| Lesson ID comes from a local page/block offset; mode is local CHOICE/SHORT_ANSWER | KP has explicit `SourceAnchor(docId,pdfPage,...)`; question has `question_kp` membership | Anchors can locate course material. They do not identify which locally generated cloze was a ledger question. No lesson/mode → course/KP/question join exists. |
| `StudyCoachCourse.practice`: prompt/reference/answer/options, generated from text | Ledger question ID, normalized content fingerprint, verified key and option IDs | Similar text, a shared KP, or even equal content hashes do not prove origin or approval. Local Practice has no stored ledger identity. |
| Local answers/reviews keyed by `(book,lesson,mode)` | `question_posed`/submission keyed by course/device/attempt; original attempt owns a frozen question snapshot | Local answering does not create a ledger attempt. A UUID or version added afterwards supplies no provenance. |
| Receipt `approvalId`, `approved`, `usedQuestions`, `posedSetComplete` | Verified pool ownership, authoritative posed events and actor transaction | Receipt is trusted source-owner transport. The local importer does not authenticate that owner, check server approval, or certify snapshot completeness/freshness. |

References: `desktopApp/.../books/TextbookLibrary.kt` (`importBook`,
`updateReviewVariantSource`); `TextbookCourse.kt` (`Practice`, `lessons`, `practice`);
`server/store/.../CourseContentStore.kt:install`;
`shared/domain/.../Content.kt` (`TextbookDocument`, `SourceAnchor`);
`LedgerStore.kt` (`registerQuestion`, `browsableSources`, `takeReviewVariant`);
`WindowsReviewEntryTest` explicitly preserves remote facts during local study.

**Conclusion:** existing facts do not establish the full bridge. Ordinary imported
books remain unknown/not-ready. PR218 can persist a separately supplied explicit
receipt; that is not evidence that any real course produced it. No real corpus,
learner database, answer key or production endpoint was read for this audit.

## Fingerprint-only identity, if an authoritative bridge is later supplied

The existing ledger identity is `(course, questionId, version=null, fingerprint)`.
`questionFingerprint` hashes normalized stem and sorted normalized option texts
with length delimiters; option IDs/order/display letters do not change it. It does
not certify the correct key, KP membership, ownership or approval. Never use the
fingerprint alone as a cross-course key, or turn it into a fabricated `version`.

`LedgerStore.reviewVariantCandidates` and `CourseHttp.reviewVariantCandidates`
already preserve null version. `takeReviewVariant` explicitly rejects a non-null
version, a missing fingerprint or a changed fingerprint. In contrast,
`BookReviewVariants.matchesOrigin/offers` currently require nonblank versions.
The v1 local receipt therefore cannot safely serve as the ledger adapter merely
by filling in `v1`, or by relaxing one null check.

A future source-owned bridge needs explicit provenance for the *whole* tuple:
file SHA and extraction/block identity → course/doc/source anchor → KP → original
question ID/fingerprint → actual original posed/submitted attempt and original
answer/guessed fact. Any absent or conflicting edge stays unknown. A source anchor
alone is insufficient. Historical generated Practice cannot be retroactively
declared a ledger attempt. Revisions must revalidate the original snapshot rather
than overwrite it.

## Posed snapshot, approved payload and consumption boundary

1. The owner must supply a course/learner scope, a stable ledger revision/cursor,
   and complete posed facts at that revision, with pagination/freshness verified.
   Exclusion includes other devices and attempts outside this local queue.
   v1 has no authenticated revision/fence or proof of completeness: missing
   `usedQuestions` and `posedSetComplete=false` are rejected, but a truncated set
   labelled `true` cannot be detected. Do not promote that declaration to proof.
2. Candidates come from the existing verified same-course/KP pool. Bind approved
   content to the exact question ID/fingerprint and owner decision; recheck
   retirement, key verification and scope at consumption. Receipt `approvalId`
   is a reference, not a new approval authority.
3. `GET /pool?kp=...` supplies identity/status, not the answer key or a ready local
   Practice payload. `VerifiedPoolQuestion` is server-only. A local Practice
   expects answer text, so dumping server keys into a receipt is not a valid
   adapter. Use the existing server-owned pose/submit/feedback route for ledger
   questions; any offline payload needs its own existing, explicit authorization.
4. The existing route is `CourseHttp.takeReviewVariant` → authenticated
   `POST /v1/courses/{course}/review-practice` → actor `takeReviewVariant`.
   It requires an actual actionable ledger review entry and freezes selection,
   consumption and `question_posed` in one server transaction. Its returned pose
   owns stem/options/letterMap, attempt, fingerprint and identity. A local entry
   string cannot replace the required ledger entry. Client-written snapshots
   cannot establish server pose provenance (`provenExactPose`).
5. A future local mirror must retain the exact server target/attempt for retry,
   reconcile the returned pose/attempt record after failures, and persist the
   verified bridge plus original/variant snapshots atomically in SQLite. There
   is no distributed transaction between today's receipt import and server
   consumption. Never claim that importing `usedQuestions` consumes the pool.
   Guessing a correct answer must continue to leave the queue entry incomplete.

The next implementation prerequisite is an authoritative producer for the explicit
book/block → ledger question/original-attempt relationship, plus a server-owned
snapshot/pose adapter. Without it, widening v1 acceptance would hide missing facts.
This audit does not authorize changing Events/Projector, the selector, server
transactions, reader files or any draft's merge state.

## Focused executable evidence

`WindowsSourceIdentityBoundaryTest` uses the actual `WindowsTextbookHost` file
picker/controller and fresh synthetic books. It covers eight rejected inputs:
book/lesson/mode mismatch; declared-incomplete/missing posed set; changed original
question/answer; fingerprint-only identity without inventing a version. At each
refusal, all rows in answers/reviews/sources/posed/variant-history are unchanged,
the start button is absent, and reopening still gives one incomplete queue item.
After the book mismatch, it answers the original correctly with “猜” and checks
that the new library still retains both original and current answers, incomplete.

Run only this new class, not the previous 18-test suite:

```sh
tools/dev/heavy --local ./gradlew :desktopApp:test \
  --tests com.coach.desktop.WindowsSourceIdentityBoundaryTest \
  --console=plain --rerun-tasks
```

The separate, unshipped `IncompletePosedClaimAuditProbe` asserts that a truncated
set labelled complete should be rejected. Its expected assertion failure records
the current limitation: omitting the fixture's known-posed `variant-a` from
`usedQuestions`, while keeping `posedSetComplete=true`, registers successfully
and selects `variant-a` again. Thus **declared-incomplete refusal is verified;
actual completeness verification is not implemented**. It is not a product
regression or a claimed red→green fix.
Both runs' actual exit codes, XML and synthetic SQLite snapshots are attached to
the issue handoff. Mac offscreen host only; Windows native/AWT/DPI remain unverified.
