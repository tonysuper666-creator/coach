# Review queue contract candidate

This candidate started as an isolated contract. TON-1448 now reads it from the
shared client's review entry (`ReviewQueueView.kt` builds the board,
`ReviewScreen.kt` renders it and remembers the last opened entry); the formal
scheduler, Events/State/Projector, the question ladder and server code remain
untouched.

`ledgerQueueCandidate` replays the real main `Event` inputs with `learnerEvidence`
and `project`, reads `Projection.review/revisit`, and migrates the actionable
`reviewsOn(day)` records. Unsupported facts and conflicting event replays are
rejected; the client entry turns the unsupported-fact rejection into an explicit
"waiting for an authoritative snapshot" state instead of an exception or an empty
queue. The mistake adapter currently covers checked single-choice answers;
constructed answers and the PR150 ladder await their real integration contracts.

Known item identity includes course, provenance boundary, KP and original question
ID/revision/fingerprint. A missing question version remains null. Multiple original
attempts and their exact submitted/grade facts are retained. Wrong, guessed and
explicitly correlated due sources form a deterministic union. A later successful
original answer removes that question from a newly built mistake snapshot.

Main's KP schedule has no original-question link. Migration therefore preserves an
unknown original, stable legacy due identity and the normal pre-exam-overlay
schedule. Sharing a KP is not evidence of sharing an original question. A caller
with an explicit original association can supply `DueOriginalLink`; otherwise an
unknown due gap and a known mistake remain separate visible items. Actual variant
allocation appends a link without changing the unknown item's identity.

`assignVariant` requires an allocated valid alternative on the same course/KP and
provenance boundary. `allocateVariants` now drives it from an injectable
`VariantCandidatePort`: it re-checks every candidate itself (same course, KP and
provenance boundary, `VALID` revision, verified key, different question than the
original, not already allocated) and only assigns what passes. A question is
spent once per scope: variants already on the queue are counted as taken before
allocation starts, and each assignment adds to that set, so two `NEEDS_VARIANT`
entries on one KP cannot become two attempts on the same question. An original
question already answered in the queue is spent as well: an unlinked due row on
the same KP cannot take that same question number back as its "variant". A port
that returns nothing, or whose candidates are all already spent, leaves the entry
as a visible missing question (`NEEDS_VARIANT`, zero variants); nothing is
invented. The store implements the port with `reviewVariantCandidates`, the same
verified, unposed single-choice pool the question route takes from. Distinct
wording, content validity verification and offline availability remain the bank's
responsibility.
Withdrawal/replacement retains original answers and all variant links, makes an
affected current variant not ready, and preserves pending sync attempt identities.
A valid alternative can remain ready when only the original is withdrawn.

`entries` is deterministic by due day then identity. `dueEntries(day)` is the one
list a future Today count and queue view can share, including not-ready gaps.
The client's review entry takes the port as an injectable parameter
(`reviewBoard(..., variants: VariantCandidatePort)` / `ReviewScreen(variantCandidates = ...)`),
allocates before it schedules, and shows a served row as "已备 N 道变式" with its
allocated attempt identity; the same identity is written to the review cursor when
the row is opened, so the practice route can resume that exact attempt. The default
`NoVariantCandidates` reports nothing, which is why the entry keeps saying "待生成
变式" until a real question-level pool channel exists on the device.
The client's board renders `scheduleOrder(...)`, which makes the known
`exam_weights_set` weights an actual ordering input. Entries not yet overdue past
the bound are ordered by weight; entries past the bound form a starving group that
is ordered by due day, oldest wait first, so weight cannot re-order it. That gives a
checkable fairness bound: a starving entry is reached after at most one pass over
the other starving entries, and a fresh high-weight entry can never jump ahead of
it. A high-frequency point cannot push a low-frequency one out indefinitely. The
exam date comes from the course configuration (`courseExamDay`/`Projection.examDay`);
inside the last seven days the waiting bound tightens. It is deterministic and
clock-free.
The serialized candidate retains entry order, cursor, source union, original
answers and pending associations. It is an immutable contract/snapshot: it does not
reconcile a fresh ledger snapshot with a saved in-flight session, acknowledge
commands, or write the ledger. That cross-device reconciliation and the question
route's own transport remain open; nothing here claims cross-device recovery.

Synthetic behavior verification, using the existing Windows slot/Idle wrapper:

```sh
tools/dev/heavy --local ./gradlew :shared:domain:jvmTest --priority=low --console=plain
```

No formal content, private exam text, device acceptance or release evidence is
implied by this candidate. What is still open on TON-1448: the transport that
feeds `VariantCandidatePort` from the pool to each client (the entry-side seam and
its synthetic-data evidence exist; a device still reports no candidates), the
practice route consuming the persisted variant attempt identity, cross-device
session reconciliation and the release path (the review entry, allocation and
weighted ordering are wired on the shared client).
