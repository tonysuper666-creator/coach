# Explicit local book ↔ course ledger bridge (TON-1448)

This implementation is stacked on PR221, head
`02d7175ff4a863ed2ea04f8c5e15dcd5d97d0553`. It implements the identity and
authenticated pose contract identified in the [earlier audit](local-book-ledger-identity-audit.md).
It supplies an internal integration API; it does not wire a new reader/UI action,
approve a production book, or turn local textbook answers into ledger commands.

## Identity and authority

`BookLedgerIdentity` explicitly names every edge:

| Local source | Authoritative ledger check |
| --- | --- |
| Original imported file SHA-256 | Owner approval hashes the actual original bytes; the library rehashes its copied file at registration and preparation. This digest is distinct from the Markdown document blob digest. |
| Lesson and original non-heading block, PDF page, UTF-16 offsets and text SHA-256 | The library checks its exact block and immutable original Practice; the server checks the same block ID/offsets/hash against the named course document page and its current Markdown blob digest. |
| Course, document, KP | Active KP in that course must explicitly anchor the named document/page; the source document must remain visible. |
| Original question ID, `version=null`, fingerprint | Current verified single-choice question must belong to the named course/KP; fingerprint is recomputed from stem/options. No version is invented. |
| Actual original attempt | Exactly one learner `question_posed` must be server-owned (`commandId=null`) and freeze that question/fingerprint/KP/stem/options/letter map. Agent evidence is excluded. |
| Original answer and guessed fact | Exactly one actual learner submission must map through the original frozen display letters to the claimed answer text. The original key, reference and local original Practice must agree; voided attempts are refused. |

An identity claim cannot approve itself. An explicit content-owner integration
calls `LedgerStore.approveBookLedgerSource(identity, originalBytes)` on the
single-writer actor. This verifies the graph and stores the exact approved tuple
in an additive SQLite table. There is deliberately no learner HTTP approval
endpoint. The owner is responsible for supplying the real association; equal
text or a shared KP does not create one. Withdrawal and live source/key changes
invalidate consumption without deleting learner history.

The v1 receipt's `approved`, `approvalId` and `posedSetComplete` flags cannot
establish this authority. Default libraries refuse v1 registration and ignore
unverified saved v1 sources after restart. The separate legacy
`BookReviewSourceAuthority` port exists for explicitly trusted local integrations;
the fingerprint-only course path uses the bridge below, not the local Practice
variant allocator.

## Registration and exact consumption

1. An owner integration supplies and approves the complete identity. A paired
   `CourseHttp.bookLedgerCoverage()` fetches the server's complete course posed
   facts: event ID, sequence, attempt and question identity, plus the current
   course cursor. Repeated questions and other devices are retained individually.
2. Supply a bounded JSON file to `BookLedgerBridge.register(review, path)`:

   ```json
   {"format":"coach-book-ledger-source/v2","identity":{},"coverage":{}}
   ```

   The two objects encode `BookLedgerIdentity` and `BookLedgerCoverage`, with all
   required fields. Neither an empty object nor an owner declaration substitutes
   for server facts. The library validates the raw file, block, original Practice
   and original answer before and after the network suspension.
3. Authenticated `POST /v1/courses/{course}/book-review-source` rechecks owner
   approval, every live edge and exact coverage in one actor transaction. Missing,
   extra, repeated, reordered or stale coverage is rejected. It locates the real
   due ledger entry carrying the original question and attempt, and allocates for
   that entry first through the existing verified same-KP candidate port. Other
   entries still participate in occupied/answered-question exclusion. It never
   relabels an unrelated due row or persists invented attempts/answers.
4. Persist `BookLedgerRegistration` in the local library. `prepare` saves that
   exact entry/candidate/attempt and proof in the existing review host state;
   `take` calls `CourseHttp.takeReviewVariant`. The review-practice endpoint
   revalidates identity, coverage and allocation, then delegates to the existing
   `LedgerStore.takeReviewVariant` in the same transaction. Server pose and the
   exact retry receipt commit together. A concurrent pose, revoked key, altered
   original or withdrawn approval rejects before consumption.
5. Successful take synchronizes the actual server-owned pose into the existing
   client SQLite ledger. `reviewPracticeTarget` and
   `restoreReviewPracticeSession` restore the exact host after database close and
   reopen. Retrying that same receipt returns its existing pose despite the
   cursor change caused by the take itself. Different receipt/target identities
   are refused. No distributed transaction between library and server is claimed:
   interrupted synchronization must resume via the existing sync and exact retry.

An ordinary book without these identities stays not-ready. The bridge does not
manufacture a ledger original from a local answer, expose unused candidate keys,
change completion/scheduling rules, or synchronize local Practice as learner
evidence. Cached registration is not proof of current server readiness; take
always checks the live server boundary.

## Focused evidence

`BookLedgerBridgeTest` uses a fresh synthetic PDF, actual content seeding,
server-owned original pose, real `submit_answer` command, paired loopback Ktor
HTTP and separate client/server/library SQLite databases. It verifies complete
registration → exact take → database close/reopen → existing-host restore →
idempotent retry, retaining one original submission. It also covers identity and
coverage mutations, cross-device races, owner withdrawal, altered raw file,
original-key withdrawal, malformed/oversized/unauthenticated/cross-course HTTP,
and refusal to revive unverified v1 sources.

`IncompletePosedClaimAuditProbe` retains the original audit assertion: omitting
the known-posed question while declaring completeness must leave the review
not-ready. It fails at PR221 and passes with the default authority boundary.
The server coverage tests independently verify actual set completeness after
valid owner approval, so blanket v1 refusal is not the sole green evidence.

Affected registration/store/route/client regressions run with the command in the
PR and attached evidence manifest. No Scout boundary suite, production data,
native Windows session, package or installation is part of this slice.
