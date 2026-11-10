# Executable synthetic runtime contract

This reference is **not** the PR150 runtime and does not authorize a question
pool. It uses invented identifiers and text only. Generate a fixture, validate
it, then run the reference consumer:

```sh
python3 tools/question-ladder/generate_runtime_fixture.py /tmp/syn-ladder.json
python3 tools/question-ladder/runtime_contract.py validate /tmp/syn-ladder.json
python3 tools/question-ladder/runtime_contract.py consume /tmp/syn-ladder.json --known-capability SYN-add
python3 tools/question-ladder/runtime_contract.py reveal /tmp/syn-ladder.json --ref SYN-Q1@v2 --synthetic-submitted
python3 tools/question-ladder/runtime_contract.py read-essay /tmp/syn-ladder.json --course-id SYN-COURSE-A --ref SYN-Q2@v1 --known-capability SYN-add --synthetic-submitted
python3 -m unittest discover -s tools/question-ladder -p test_runtime_contract.py
```

The producer emits immutable question/version references, separate question
format and teaching kind, target and required capability IDs, and separately
recorded prerequisite references. Aliases resolve prerequisite references only:
the consumer retains the original references and does not rewrite attempts.
An alias is a version successor of the same question ID, not a redirect to a
different question. Both ends must have the same KP, capability and format.
For this synthetic contract only, alias versions must use canonical `vN`
positive integers, and the destination must have a larger numeric version.
Other real-world version formats need an explicitly agreed ordering before
they can be used as aliases. `teaching_kind` is currently a nonempty free
string, not an enumerated question format.
Question references are a nonblocking ordering relation; they do not mean the
learner must have answered a particular question. The synthetic consumer gates
on explicitly supplied capability evidence, not question order. Null means
unknown, so an unknown prerequisite does not become an empty requirement.

`applicable_kps` is the entire applicable scope only when its producer has
actually established it. Null yields an unknown denominator, not zero. A list
is an assertion to be independently verified; this program cannot prove that
it exhausts the curriculum. Essay model answer and rubric are explicitly
nullable. Missing either prevents synthetic readiness, and the answer and
rubric are not sent in the answer-free consumer projection. A synthetic
submission acknowledgement and the course and identity checks make the full
model answer and rubric available via `read-essay`; `reveal` accepts MCQs only.
This demonstration does not capture an actual answer, grade it,
or establish mastery. Superseded versions remain in the history but are not
offered as exercises. Real essay text is
not present or authorized here. MCQ keys are likewise omitted from projection.

## Optional synthetic identity and scope extension

Existing v1 packs and the `generate`, `validate`, `consume`, and `reveal`
entry points remain supported, except that `reveal` now rejects essays. The generator accepts an optional second argument
for `course_id`; exercise refs are unique within a pack, so readers must use
the pair `(course_id, question ref)` rather than treating refs as global.
`read-essay` requires that exact course, an essay ref, known resource identity,
synthetic readiness and the submission acknowledgement. The response binds
course, question ID, version, KP and capability to `model_answer_id`, ordered
`rubric_point_ids` and their corresponding full texts. `essay_identity` also
contains `linked_question_refs` for associated MCQs. The response retains those
recorded refs and separately supplies alias-resolved refs; aliases never rewrite
history. Missing course, identity, answer or rubric, a wrong course/version,
MCQ ref or absent acknowledgement cannot produce the essay. A missing or null
course is accepted as unknown by `validate`, but `read-essay` rejects it.

The acknowledgement and supplied course are **synthetic inputs, not identity
authentication or an authorization mechanism**. A production service must
authenticate and authorize the caller and submission server-side, separately
validate association and resource versions, and enforce release and privacy
rules. Do not serve the raw pack to learners: it contains keys and essay texts.
Only `consume` is the answer-free projection. No real essay text or permission
is granted by this extension.

`applicable_capabilities` is either null or a complete declared list of
`{kp, capability}` pairs, separate from `applicable_kps`; null yields a null
capability denominator and null gap list. The list is a producer assertion,
not proof of a complete curriculum. Each KP/capability gap includes active
candidate refs and one of `candidate_present_unverified`, `candidate_missing`,
or `unknown`. A missing candidate is asserted only when the separate
`candidate_inventory_complete` is true; false/null means unknown. Superseded
refs are excluded from gap counts but retained in historical records. None of
these states claims learner mastery, semantic coverage or release approval.
Scope and inventory assertions must be verified independently for real data.
`required_capabilities` are not restricted to `applicable_capabilities`: the
latter declares target scope, while a prerequisite can lie outside that scope.
This contract does not verify such prerequisite IDs against an external catalog.

PR133 describes candidates and proposed identity relations. PR150 commit
`c1386062b0b77f7953dac8ec87bbcf7093c50346` has an explicitly injected
synthetic v1 adapter for the older PR133 fixture. It does not read the optional
identity/scope extension above. Its synthetic essay exercise and a full-text
reader remain unsupported. The existing production candidate path is separate:

* PR150's production path reads the weekly item's `learning` with
  `contract_version: 1.0.0` and `runtime_approval`; its opt-in synthetic path
  separately reads base v1. Neither path consumes this identity/scope extension
  or the screening ledger.
* PR150 selects from seven `kind` values: `try_first`, `introduction`,
  `concept`, `detail`, `application`, `bridge`, and `exam_prototype`. Synthetic
  `teaching_kind` is a free string, and `capability` is not a client KP ID.
* Production `prerequisite_kp_ids` are KP IDs, never question refs or capability
  IDs. At this commit a nonempty list is checked against the outline KP catalog;
  a missing catalog or out-of-catalog ID leaves the prerequisite unknown and
  withheld. The producer candidate contract does not settle whether a future
  authorized catalog can include out-of-outline KPs: that policy is unknown.
  Do not map unknown to an empty list.
* Production `learning.essay` holds answer/rubric IDs; a full-text reader is
  pending. The synthetic essay resource is not an approved production mapping.
* Outline `weekKps` is not an independently verified full denominator. PR150
  retains missing/null/unknown/unverified star state while weight 1 is only a
  scheduling fallback, not a verified one-star classification.

The comparison above is a source inspection of the frozen PR150 head, not a
new UI run or proof that this extension is integrated. Production requires an agreed
adapter, independently verified immutable input hashes, actual answer and
isolation evidence, explicit release approval, and an authorized real pool;
none is supplied by the synthetic fixture. The fixture cannot prove mastery,
coverage or publication readiness.
