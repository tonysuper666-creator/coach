# Weekly question ladder runtime adapter

TON-1447 consumes TON-1453's synthetic candidate contract from PR133 head
`4a5042b8c3adc8e596a8dabcd28472a120e6ffa5`. This adapter is proposed for
author/content-review agreement. It does not grant approval to any candidate,
migrate private content, or replace TON-1453's immutable snapshots.

The original `mcq-mainline/Wnn.json` pack remains the only runtime question
source. An independently approved candidate may be attached as the `learning`
object of its corresponding pack item. Its ID, stem, options, answer and KP
must match that item exactly. Existing `setup`, `detail`, and `bridge` storage
labels are retained; `learning.kind` supplies the teaching sequence. Original
IDs, attempts and old private snapshots remain intact. Nothing reads original
exams or imports a second question generator.

The candidate retains all v1 fields, including immutable sources, utility,
prerequisites, variant identity, priority and calculation/essay associations.
It additionally has a separate `runtime_approval` object:

```json
{
  "screening_decision": "keep",
  "isolation_state": "CLEARED",
  "release_state": "APPROVED",
  "independent_key_evidence": "private-review-id",
  "utility_review_evidence": "private-review-id",
  "originality_review_evidence": "private-review-id",
  "candidate_sha256": "sha256-of-canonical-candidate-excluding-runtime_approval"
}
```

Evidence references are private records, not author assertions of verification.
The approved hash binds all candidate text, explanations and relations. Hash
UTF-8 JSON with recursively sorted object keys, preserved array order, no
whitespace, and ordinary JSON escaping (`com.coach.domain.canonicalJson`).
Both the original item and candidate must have `key_state=VERIFIED`. Missing,
changed, removed, isolated, unreviewed or unreleased candidates are withheld
from a new learning session. A storage label alone never authorizes supply.
Source documents must be approved teaching sources, never original exams.
The server rejects submissions to newly withheld items; an already recorded
submission ID remains idempotent and retains its original outcome.

`GET .../mainline/{week}?layout=shuffled-v1&learning=v1` returns only eligible
items, a withheld count, the existing notes, and answer-free `LadderQuestion`
metadata. It does not send keys, remember sentences, explanations, candidate
approval records or source text before submission. The legacy endpoint keeps
its established wire format for older clients. The v1 POST carries `learning`,
`dontKnow`, `assisted` and the existing immutable submission ID/layout. Direct
explanation writes a blank answer and an explicit `dontKnow` event field;
it never substitutes the correct option. Guessed answers do not count as
independent successes. Diagnostic questions are marked `tryFirst`, excluded
from mastery, review supply and correct-answer totals, and retained as learning
history. Projection cache version 11 requires a fresh fold of those semantics.

The shared pure reducer chooses from these same IDs. It runs 2–4 available
diagnostic questions first, follows prerequisites and each point's teaching
order, interleaves source-backed priority, inserts an unseen easier question
or variant after failure/guess/direct explanation, and skips redundant
introductions after two independent successes. Exhaustion is shown as a gap;
it never repeats the same fingerprint indefinitely. Stars set base weights
1–3; verified lecture emphasis and weakness signals each add one only when
their respective source field is present. TON-1448 can consume the same
answer-free priority metadata instead of inferring a separate priority.

The client persists the reducer alongside the existing selected option,
feedback, pending request, notes position and question position. Six submissions
open a checkpoint at the next transition. Its sentences come only from that
round's returned `remember` fields, deduplicated; diagnostics do not supply
recap facts. End-of-section status uses the real course projection and keeps
outline points with no candidate visible. Reading and cited followups leave
the question state in place. Followup before answering is recorded as help.

## Required before acceptance

TON-1453 must agree this runtime envelope and deliver a concrete immutable
approved version with actual content/review/release receipts. Its current
UNREVIEWED private candidates cannot exercise production supply. Alias and
capability mappings still require their immutable version/hash-bound contract;
they cannot be inferred from question labels or this adapter's KP list.
Complete calculation/essay handoffs, all-point denominator reconciliation,
same-head Mac server/UI behavior tests, real window screenshots, offline/restart
checks and the independent reviewer/cross-review gates remain acceptance work.
Synthetic reducer tests and successful compilation do not satisfy those gates.
Do not merge or package the default strict route until approved supply and
the required runtime evidence are available.
