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

## User flow and persistence

The existing Review tab has “继续第 N 周”, “看本周要点” and “选其他周”.
Continue targets the previous week if available, otherwise the first week with
unmastered facts, otherwise the first week. From the paired, course-selected
Today screen, Review → Continue takes two clicks. Review → Notes → Skip takes
three. Selecting a week in the picker opens notes, with a separate direct-quiz
button. Section practice filters questions by intersecting fact IDs. Answer
pages link back to the relevant sections; bridge links preserve their return
path through step grading and essays. Today and the five navigation tabs stay
as before.

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
tools/dev/heavy ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest \
  -PcoachApplicationId=com.coach.android.ton1394 -Pcoach.course=ton1358-synthetic-mainline --console=plain
tools/dev/heavy --emulator python3 tools/dev/check-mainline-notes.py --output build/ton1394-evidence
```

The harness owns/reaps one `coach_1128_c70_r`, a fresh empty candidate database,
a synthetic bridge and an isolated tailnet proxy. It never reads production
credentials/data. It uninstalls both card APKs, restores emulator dimensions and
stops its processes in `finally`. No physical devices, learner CLI or production
ports are used. It records two exact pixel sizes, actual cold/warm click paths,
section filtering, mastery, process restart, 40 raw UI timing samples and injected
load/committed-response loss recovery. Fault samples are reported separately.
Timing starts immediately before the submit action and ends when an explanation
appears in the real accessibility tree; it is a conservative UI observation,
not localhost HTTP timing. Its nearest-rank p95 must be at most 800 ms.

## Current verification status (TON-1394 draft)

JVM suites pass (server 208, client 74, UI 100), as do the two content-tool
checks and the isolated Android application/test APK builds. Real emulator
acceptance has **not passed**. Two navigation-locator attempts stopped at
`No clickable ancestor` and `Missing synthetic mainline UI: ↻` respectively.
The glyph is not exposed as accessibility text; the next owner must select the
real clickable navigation label and verify page transitions. Do not remove or
relax the acceptance assertions. Only the first attempt produced entry and
first-question screenshots at 2448×1848. There is no validated full-week flow,
1972×1248 layout, fault recording or p95 result. The protocol above describes
what the harness is intended to collect, not completed acceptance evidence.

## Research adoption

TON-1392's delivered six-product table recommends preserving Review as the main
entry, splitting Continue from Read notes, linking feedback to source sections,
and showing fact-based coverage rather than fabricated adaptive claims. This
implementation adopts those recommendations. It adds no extra tab, daily
streak, leaderboard, paywall, unlock rule, AI-generated mastery or prerequisite
barrier. Real W1 prose and content quality are a later product acceptance step;
only synthetic material is used for engineering evidence here.
