# Question ladder candidate contract, v1

Status: proposed for TON-1447 integration; not supported by the current parser,
not an authorization to publish. A change requires a new version. This format
is an independent private candidate, not a replacement for a mainline pack.

Each candidate has a globally stable `id`, `course_id`, `week`, `section_id`,
and `kp_id`; `fact_ids` reference the frozen source facts. `kind` is one of
`introduction`, `concept`, `detail`, `application`, `bridge`, `exam_prototype`,
or `try_first`. `try_first` is displayed as “先试试” and must never count
toward mastery. Teaching explanations refer back to relevant `try_first` IDs
and their displayed positions, not just a hard-coded number.

Each candidate also has `prerequisite_kp_ids`, a non-negative `difficulty`, and
`variant_group_id`. A variant group represents one understanding, not just a
reordering of options; distinctness requires content review. Questions have
`stem`, `options`, zero-based `answer`, `remember`, and `explanation` with
`correct`, one `wrong` entry for every non-answer option, and `background`.
An introduction contains enough information for a beginner in its stem.

`sources` are ordered by authority: textbook, slides, then recordings; each
has `kind`, a stable private `document_id`, a page or section locator, and a
private exact-text anchor sufficient for the reader to find the source. The
anchor and all real content stay on the authorized Mac. Recording identity and
timestamps cannot be asserted without source evidence. `key_state` begins
`UNREVIEWED`; independent answer verification, isolation and release approvals
are separate evidence and gates. No candidate is automatically scored or
loaded in production. Preserve existing IDs, keys, states and historical
snapshots; do not write candidates over the existing three-tier packs.

An incremental manifest freezes this contract version, input and output hashes,
source versions, author, per-question independent verification evidence, and
isolation/release state. W7 has no new course content. W8 and later may use
only approved material and existing per-week gates; held-out exams are excluded
from writing. The exam date is 2026-12-15. Store the manifest and real records
privately. The example in this directory is entirely synthetic.

## Proposed utility and weighting extension

These fields are a proposal for the existing v1 candidate, not an accepted
integration contract. Every original or new question has a specific `utility`
with a teaching rationale and private evidence references (approved exam
analogue, verified lecture emphasis, handbook objective, textbook core, or
necessary introduction/bridge). Unknown evidence remains pending. A screening
decision is exactly one of `keep`, `rewrite`, or `remove`; remove excludes an
item from the effective pool without erasing its ID, history, or attempts.
Rewrites require a new independent key check. A draft decision is not release
approval and source identity, isolation, and key checks remain independent.

Each applicable KP has a source-backed `star` (1, 2, or 3), and explicit
lecture-emphasis and weakness signals with their provenance and uncertainty.
The downstream author must agree how these signals change learning/review
priority; a label alone does not implement weighting. Unknown is not zero.
For each KP, separately record ladder kinds and content-reviewed distinct
variants: stars 3 and 2 require the full ladder and at least 6 and 4 variants,
respectively; star 1 requires concept and detail MCQs. Swapping option order
does not create a variant. Count questions and reviewed distinct variants
separately, with numerator/denominator and unknown or isolated KPs visible.

Calculation candidates link each ordered step to the complete example. Essay
candidates link MCQs covering structure, arguments and examples to rubric
points and a full model answer. Records give stable IDs for the independent
exam crosswalk but do not contain held-out material. Independent QA alone
determines subquestion coverage, and the holdout is used only in the final
check. Per-week original decisions must reconcile to frozen originals; new
questions are separate. A different vendor independently samples decisions by
week, star and kind; this does not substitute for independent key checking.

`tools/question-ladder/screening.py` creates a private ID-only ledger from a
frozen inventory, rejecting changed packs or duplicate IDs, and emits only
counts by week, origin, star and pedagogical kind. It never infers a decision,
star, distinct variant, isolation clearance or release eligibility. Non-pending
decisions need rationale and evidence; assigned stars need a source. The ledger
itself and any evidence locators stay on the authorized Mac.
