# Executable synthetic runtime contract

This reference is **not** the PR150 runtime and does not authorize a question
pool. It uses invented identifiers and text only. Generate a fixture, validate
it, then run the reference consumer:

```sh
python3 tools/question-ladder/generate_runtime_fixture.py /tmp/syn-ladder.json
python3 tools/question-ladder/runtime_contract.py validate /tmp/syn-ladder.json
python3 tools/question-ladder/runtime_contract.py consume /tmp/syn-ladder.json --known-capability SYN-add
python3 tools/question-ladder/runtime_contract.py reveal /tmp/syn-ladder.json --ref SYN-Q2@v1 --known-capability SYN-add --synthetic-submitted
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
submission acknowledgement makes the full model answer and rubric available
via `reveal`; this demonstration does not capture an actual answer, grade it,
or establish mastery. Superseded versions remain in the history but are not
offered as exercises. Real essay text is
not present or authorized here. MCQ keys are likewise omitted from projection.

PR133 describes candidates and proposed identity relations; PR150 at
`08e74726` reads approved candidates only against original mainline records and
uses prerequisite **KP** IDs. Capability IDs, aliases, essay text/rubrics and a
complete applicable KP denominator are not supported by that interface. This
reference does not claim the two are integrated. Production requires an agreed
adapter, independently verified immutable input hashes, actual answer and
isolation evidence, explicit release approval, and an authorized real pool;
none is supplied by the synthetic fixture. The fixture cannot prove mastery,
coverage or publication readiness.
