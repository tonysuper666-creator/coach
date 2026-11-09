# Weekly notes and question continuity

The optional private `mcq-mainline/W01-notes.json` sits next to the existing
`W01.json` question pack and `W01-facts.md`. Existing seven-week packs need no
migration. A missing/invalid notes file shows a readable notice and leaves the
questions available. No private course prose belongs in this repository, PR,
issue comments or acceptance attachments.

## Authoring contract

Authors can write `W01-讲解.md` offline using stable markers:

```markdown
<!-- coach-week: 1 -->
<!-- coach-section {"section_id":"W01-orchard","fact_ids":["F1","F2"],"sources":["Synthetic slide 1"]} -->
## Synthetic orchard
Pears grow here. Plums ripen later.
```

Conversion (paths below are placeholders for private local directories):

```sh
python3 tools/mainline/content.py notes --input PRIVATE/W01-讲解.md \
  --facts PRIVATE/W01-facts.md --week 1
python3 tools/mainline/content.py notes --input PRIVATE/W01-讲解.md \
  --facts PRIVATE/W01-facts.md --week 1 --output PRIVATE/W01-notes.json
```

Omitting `--output` is a dry run. JSON input is also supported. Version 1 has
`schema_version: 1`, numeric `week`, and nonempty `sections`. Each section has a
stable ASCII `section_id` (1–96 letters/digits/underscore/dot/hyphen, first
character alphanumeric), nonempty `title` and Markdown `body`, distinct known
`fact_ids`, and `sources` equal to the set of sources of those facts. All weekly
facts must be covered; overlap between sections is allowed. IDs survive title
and prose edits; do not derive IDs from heading text. Both converter and server
check coverage, unknown facts, duplicate section IDs and source consistency.
The server whitelists fields and emits generic validation errors without prose.

GET `.../mainline/1?layout=shuffled-v1` adds `notes`, `notesError` and item
`factIds` to the existing answer-free payload. Notes contain `section_id`,
`title`, `body`, `fact_ids`, `sources`, `mastered`. Answer replies include updated
notes mastery. A section is mastered only if **every** linked fact has at least
one question and **all** questions for each fact have a latest verified,
correct, non-guessed answer. A missing/unverified key cannot master a fact.

## Explanation-only batches

```sh
python3 tools/mainline/content.py explanations --original PRIVATE/original \
  --candidate PRIVATE/candidate --report PRIVATE/dry-run.json
python3 tools/mainline/content.py explanations --original PRIVATE/original \
  --candidate PRIVATE/candidate --report PRIVATE/apply.json \
  --apply --backup PRIVATE/new-backup-directory
```

Both directories must contain the same nonempty set of `Wxx.json` files.
Only `sections[].items[].explanation` may change. IDs, stems, options, keys,
fact links, sources, metadata and all other structure remain invariant.
The report has counts and before/after SHA-256 snapshots; it contains no prose.
An apply validates the whole batch before backing up every original and writing
a snapshot; then it replaces files atomically and rolls back on failure. Use a
quiet private authoring directory, not a live concurrently edited library.
`test_content.py` exercises a **24-file** dry run, rejected key edit, backup and
apply. These are synthetic files, not claims of editing real course content.

### Independently authorized correction snapshots

When QA and the independent cross reviewer have frozen a correction snapshot,
use a fresh working copy of that snapshot as `--original`, keeping the raw
pre-correction library and approved snapshot immutable:

```sh
python3 tools/mainline/content.py explanations --baseline PRIVATE/pre-correction \
  --original PRIVATE/approved-workcopy --corrections PRIVATE/corrections.json \
  --candidate PRIVATE/explanations --report PRIVATE/two-stage-dry-run.json
```

The tool checks two boundaries in the same invocation: raw library → exact
authorized corrections, then corrected working copy → explanation-only edits.
`--baseline` and `--corrections` must be supplied together. Omitting both retains
the original strict explanation-only workflow. `--apply --backup PRIVATE/new-dir`
backs up and updates only the working copy; it never writes the raw baseline,
facts files or independently approved snapshot. Reports/backups must be outside
the immutable baseline. A second application requires a fresh approved working
copy, since the frozen correction hash must still match before explanation edits.

Example **synthetic** ledger (replace hashes with complete SHA-256 digests):

```json
{
  "schema_version": 1,
  "authorization": {
    "issue": "SYNTHETIC-ONLY",
    "qa": "independent QA evidence reference",
    "cross_review": "independent cross-review evidence reference"
  },
  "files": [{
    "file": "W05.json",
    "original_sha256": "FULL_ORIGINAL_HASH",
    "corrected_sha256": "FULL_APPROVED_HASH",
    "changes": [{"item_id": "S1", "field": "answer", "before": 0, "after": 1}]
  }]
}
```

The ledger permits exact changes to existing item fields `stem`, `options`,
`answer`, `key_state`, `source`, `sources`, and `fact_ids`. Each file/item/field
must be listed once with its exact old/new JSON value, and file hashes must
match. Everything else, including IDs, item counts, order and metadata, remains
unchanged. An unlisted edit is rejected even inside an otherwise authorized file.
New questions cannot be counted as rewrites. Answer booleans are distinct from
numeric indexes. The second boundary permits only explanation changes, even to
items whose keys were authorized for correction in the first boundary.

Approval references are mandatory metadata, **not authenticated signatures**:
the caller must obtain actual QA/cross-review approval through the correction
issue. This tool does not assess factual correctness, grant authorization,
edit facts/keys, import candidates or restore reviewed states. Correction ledger
values stay private; public reports contain only counts and hashes. The tests
cover allowed W05/W06 synthetic corrections, rejected unlisted changes, stale
hashes, extra questions, rewritten keys/states and missing review references.

## Class emphasis metadata

The optional private `W05-emphasis.json` uses the normalized interface below.
It is separate from notes, so related questions can show stars even when notes
are absent. Only records linked to existing weekly facts and marked independently
reviewed can publish. Do not invent markers or import supplemental questions to
make validation pass. F/S IDs are treated equally once present in the actual
fact list; unimported supplemental IDs remain in the private pending mapping.

```json
{"schema_version":1,"week":5,"marks":[{"fact_id":"F1","recording_kind":"lecture","recording_id":"L1","timestamp":"98:00","summary":"Synthetic pears were emphasized.","reviewed":true}]}
```

```sh
python3 tools/mainline/content.py emphasis --input PRIVATE/approved-mapping.json \
  --facts PRIVATE/W05-facts.md --week 5 --inline PRIVATE/W05-讲解.md \
  --output PRIVATE/W05-emphasis.json
```

Omit `--output` for dry-run and `--inline` when only the independent mapping is
used. Reports contain counts only. This is an explicit normalized adapter for
the private authoring material, not an automatic factual verifier of arbitrary
Markdown. A bare timestamp cannot identify its fact or recording. Inline entries
therefore carry explicit context before the author marker, using the same schema:

```markdown
<!-- coach-emphasis {"fact_id":"F1","recording_kind":"lecture","recording_id":"L1","summary":"Synthetic pears were emphasized.","reviewed":true} -->【课上强调 98:00】
```

Both inputs merge/deduplicate by week, fact, recording kind/ID and normalized
time. Conflicting summaries require private review instead of picking a winner.
`86:00`, `98:00`, `121:03` and `02:01:03` are supported without truncating long
recordings; output is normalized to hours/minutes/seconds. Unknown facts,
unreviewed records, absent context, invalid timestamps or blank summaries fail
conversion. Keep pending records separately; only the QA/cross-reviewed subset
belongs in the published mapping. The server independently validates and
whitelists metadata, withholding an invalid optional file without blocking the
question pack. `reviewed` is caller-supplied metadata, not approval authentication.

Notes and related questions show an accessible “★ 课上强调” text button only
when linked metadata exists. Its scrollable dialog identifies fact/week/recording,
time and summary. Closing it preserves the original question, choice, reading
position and guessed/mastery rules. Metadata travels in the existing cached week
body, so the database reopen regression also exercises the overlay with a
simulated unavailable server. No answer/key fields are forwarded in this payload.

The latest W5 handoff requires the real private sample after independently
approved mapping is available (16 records including 5 supplemental IDs; latest
36-candidate version supersedes 33). That integration, independent verification
and real App acceptance remain pending with Expert. Synthetic tests do not
establish W5 factual correctness or publication readiness. Preserve approved
snapshot hashes and keep all private prose/screenshots off Git and public evidence.

## User flow and persistence

The existing Review tab has “继续第 N 周”, “看本周要点” and “选其他周”.
Continue targets the previous week if available, otherwise the first week with
unmastered facts, otherwise the first week. From the paired, course-selected
Today screen, Review → Continue takes two clicks. Review → Notes takes two
clicks; Skip is a separate action. Selecting a week in the picker opens notes, with a separate direct-quiz
button. Section practice filters questions by intersecting fact IDs. Answer
pages link back to the relevant sections; bridge links preserve their return
path through step grading and essays. Focus reading hides the surrounding
navigation; its fixed toolbar keeps Exit, Skip and Section practice available.

## Reading interactions

The reader uses a serif body, 20sp/32sp type and a maximum width of 720dp,
with local light/dark and focus preferences. Existing Markdown/math rendering
is preserved. Formatted sections offer “选择原文、划线或追问” to select the exact
original text, avoiding transformed offsets. Plain sections are directly
selectable. Only a nonempty selection exposes Highlight, One-sentence note,
Explain again and Example. A collapsed selection is retained across focus loss
until an action or explicit dismissal, so clicking a selection action cannot
discard its input.

Highlights/notes save immediately in SQLite under a digest of the configured
server/device credential identity, course and week. Each mark retains stable
section ID, content SHA-256, full original body, exact offsets and quote. An
updated body/version visibly marks the saved quote as stale; it never silently
attaches to different text. Wrong and guessed results show related personal
quotes/notes by fact/section intersection. Personal notes are never source evidence
and are not included in a selection request. Reading position remains local.

Selection explanations use the existing subscription bridge and existing
learner-visible course page retrieval. The server revalidates the quote, offsets
and content version; missing pages return “无法确认” without calling AI. The
bridge sees only the selected passage and vetted source pages. Each response
paragraph must pass the existing citation/quotation gate; source buttons use
actual document/page metadata, never AI-invented page locators. AI elapsed time
is separate from quiz submission timing. Requests have a 90-second bound;
cancellation is scoped to device/course/request, including cancellation that
arrives before the request. Leaving the dialog cancels; failure/retry preserves
reading and quiz state. No background AI request or permanent chat is added.
Automated bridge tests use a synthetic stub, not a live subscription response.

The expandable weekly map connects section → fact → question. Every node uses
ordinary accessible buttons. Fact mastery requires all linked questions to be
verified non-guessed successes; guesses stay pending. Questions retain their
current statuses after each answer. Independently approved class emphasis uses
the same existing overlay in notes, map and quiz; unapproved data stays absent.

Both Android and desktop use the existing SQLite `sync_meta` table, namespaced
by course/week. It stores route, selected section/filter, reading and quiz scroll,
original question ID, displayed shuffle/layout, choice, explanation and pending
submission. It survives activity/process/database reopening. Reading position
is local to each device; it is not a new cross-device ledger fact. Mastery
continues to come from the existing shared ledger. “重新读取本周内容” explicitly
refreshes content and mastery without changing the server's question pack.

A pending submission is committed locally **before** network I/O. Retrying
reuses its immutable submission ID, layout, displayed choice and guessed bit.
408, 429, 5xx, malformed response and network failures retry automatically every
1.5 seconds while the screen is active; reopening resumes the stored pending
submission. The UI explains that the answer is saved and disables edits until
the response is recovered. Terminal 4xx remains visible with an explicit retry.
No background service is introduced.

## Reproducible synthetic acceptance

All build/test/emulator commands must use the current `tools/dev/heavy`:

```sh
tools/dev/heavy ./gradlew :server:app:test :shared:client:jvmTest :shared:ui:jvmTest --console=plain
python3 -m unittest discover -s tools/mainline -v
tools/dev/heavy ./gradlew :server:app:installDist :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest \
  -PcoachApplicationId=com.coach.android.ton1394 -Pcoach.course=ton1358-synthetic-mainline --console=plain
tools/dev/heavy --emulator python3 tools/dev/check-mainline-notes.py --output build/ton1394-evidence
```

The harness owns/reaps one `coach_1128_c70_r`, a fresh empty candidate database,
a synthetic bridge and an isolated tailnet proxy. It never reads production
credentials/data. It uninstalls both card APKs, restores emulator dimensions and
stops its processes in `finally`. No physical devices, learner CLI or production
ports are used. It records two exact pixel sizes, actual cold/warm click paths,
section filtering, mastery, process restart, 40 raw UI timing samples and injected
load/committed-response loss recovery and a 3.5-second proxy disconnection after
commit. This deliberately drops HTTP connections on the isolated path; it does
not toggle the emulator's network settings. Fault samples are reported separately.
Timing starts immediately before the submit action and ends when an explanation
appears in the real accessibility tree; it is a conservative UI observation,
not localhost HTTP timing. Its nearest-rank p95 must be at most 800 ms.

## Current verification status (TON-1394 draft)

JVM suites pass (390 tests: server 214, client 74, UI 102; zero failures,
errors or skips), as do the six content-tool checks, server distribution and
isolated Android application/test APK builds. The reading interactions above
are implemented and covered in part by JVM tests; device acceptance has
**not passed**. The smoke test now selects the real accessible navigation label,
which reaches Review. Two current attempts both stopped waiting for
`继续第 1 周`: the accessibility tree still showed `正在连接…` and `Mac 未连接`.
The diagnostic attempt's in-app health probe passed; the proxy recorded HTTP
200 for health, snapshot, events and the mainline index. Its WebSocket upgrade
requests returned 400 (the isolated HTTP proxy does not tunnel WebSockets).
These observations do not establish why the Continue button failed to update.
Keep the failure report and sanitized method/path/status diagnostics; do not
remove assertions or claim network reachability proves a completed UI load.

Both attempts exited 1 and were stopped under the two-failure escalation rule.
They produced only early entry screenshots at 2448×1848. There is no validated
full-week flow, 1972×1248 layout, cold/warm click count, restart/fault recording,
live subscription explanation timing or submission p95 result. C reading
interactions and actual private W5 integration still need device acceptance.
The protocol above describes what the harness is intended to collect, not
completed acceptance evidence. All isolated processes and card APKs were
cleaned after each attempt; production and physical devices were untouched.

## Research adoption

TON-1392's delivered six-product table recommends preserving Review as the main
entry, splitting Continue from Read notes, linking feedback to source sections,
and showing fact-based coverage rather than fabricated adaptive claims. This
implementation adopts those recommendations. It adds no extra tab, daily
streak, leaderboard, paywall, unlock rule, AI-generated mastery or prerequisite
barrier. Real W1 prose and content quality are a later product acceptance step;
only synthetic material is used for engineering evidence here.

TON-1392's later Infinite Reader report used the public Alice sample and seven
real screenshots; its AI path required sign-in and was not tested. We adopt its
serif/width/theme/focus reading, exact original-quote notes, explicit grounded
selection actions, navigable fact map and direct week entry. Those observations
belong to the researcher; this PR's acceptance uses our synthetic course. We do
not upload course material to that service. The section practice entry remains
fixed and visible while reading long text.
