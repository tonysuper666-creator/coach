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
