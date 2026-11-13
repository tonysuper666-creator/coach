# Review queue contract candidate

This candidate started as an isolated contract. TON-1448 now reads it from the
shared client's review entry (`ReviewQueueView.kt` builds the board,
`ReviewScreen.kt` renders it and remembers the last opened entry); the formal
scheduler, Events/State/Projector, the question ladder and server code remain
untouched.

`ledgerQueueCandidate` replays the real main `Event` inputs with `learnerEvidence`
and `project`, reads `Projection.review/revisit`, and migrates the actionable
`reviewsOn(day)` records. Unsupported facts and conflicting event replays are
rejected. The mistake adapter currently covers checked single-choice answers;
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
provenance boundary. Selection, distinct wording, content validity verification
and offline availability are the allocator's responsibility. Withdrawal/replacement
retains original answers and all variant links, makes an affected current variant
not ready, and preserves pending sync attempt identities. A valid alternative can
remain ready when only the original is withdrawn.

`entries` is deterministic by due day then identity. `dueEntries(day)` is the one
list a future Today count and queue view can share, including not-ready gaps.
The serialized candidate retains entry order, cursor, source union, original
answers and pending associations. It is an immutable contract/snapshot: it does not
reconcile a fresh ledger snapshot with a saved in-flight session, acknowledge
commands, allocate questions, or write the ledger. Those integration decisions,
weighted scheduling/retention and low-frequency-topic coverage remain on TON-1448.

Synthetic behavior verification, using the existing Windows slot/Idle wrapper:

```sh
tools/dev/heavy --local ./gradlew :shared:domain:jvmTest --priority=low --console=plain
```

No formal content, private exam text, device acceptance or release evidence is
implied by this candidate. What is still open on TON-1448: actual variant
allocation, weighted scheduling/retention and the cross-platform release path
(the review entry itself is wired on the shared client).
