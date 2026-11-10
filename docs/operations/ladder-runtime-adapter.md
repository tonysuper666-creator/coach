PR150 consumes the public `synthetic-runtime-contract-v1` example frozen at
PR133 `ca7a33407e22c7f33f9db2b3d2a73e18ed2a2832`. This is a synthetic adapter;
it does not import PR133's production candidates or grant their release approval.
The existing candidate `1.0.0` / `runtime_approval` path retains its approval checks.

`testdata/question-ladder/synthetic-runtime-v1.json` is that revision's
`generate_runtime_fixture.py` output, encoded as UTF-8 with LF line endings. SHA-256:
`2e77e068d75b9650e40d97f79658a07ee7adad470a4eb528ca605269f03cdad8`.
No question text or assets from a real course are used.

| Producer field | Consumer meaning |
| --- | --- |
| `id`, `version`, `ref` | Question identity, immutable version, mainline item ID=`ref`. |
| `kp`, `capability`, `teaching_kind` | Separate KP, capability and teaching sequence. Unknown teaching kinds stay unsupported. |
| `required_capabilities` | Explicit capability gate. KP mastery cannot stand in for this evidence. Null/omitted is unknown, `[]` is known empty. |
| `prerequisite_refs`, `prerequisite_aliases` | Retain recorded refs and separately resolve ordering refs. They do not require prior answers. Aliases must preserve question/KP/capability/format and advance canonical versions. |
| `applicable_kps` | Declared synthetic scope, including null. Never proof of a complete real curriculum scope. |
| `format=mcq` | Adapt stem/options without key; acknowledgement reveals a reference key, with no ledger grading or mastery writes. |
| `format=essay`, `model_answer`, `rubric` | Parser/reveal preserves the producer's post-submission contract. The MCQ mainline does not implement an essay exercise and explicitly reports it unsupported. No MCQ-to-essay link is inferred. |

For the synthetic MCQ adapter only, section grouping uses `kp`, variant grouping
uses the unversioned `id`, difficulty and scheduling weight have neutral defaults.
These are local navigation/scheduling conventions, not producer evidence of
teaching difficulty, verified stars, sources or release permission.

The adapter requires explicit code injection:

```kotlin
val runtime = SyntheticLadderRuntime.parse(rawProducerJson)
val mainline = Mainline(library, syntheticRuntime = runtime,
    syntheticCapabilities = explicitlyKnownSyntheticCapabilities)
```

Only course `SYNTHETIC`, week 1 uses that injection. The application's default
`Mainline(library)` never enables it. This permits the actual weekly response,
CourseHttp, selector and mainline UI to exercise the contract without exposing an
unapproved pool in production.

Existing candidate consumption additionally preserves `capability_id` and keeps
capability progress separate within a KP. `prerequisite_kp_ids` is checked against
the actual outline KP catalog; a question ID is rejected as
`prerequisite_id_is_not_a_kp`. That catalog validates identity only, not a complete
coverage denominator. Missing prerequisite declarations remain unknown. Scheduling
weight 1 is a fallback; `priorityStar` stays null with distinct `missing`, `null`,
`unknown` or `unverified` state unless a numeric star has source evidence.

After submission the actual UI consumes the candidate's `essay.model_answer_id`
and `essay.rubric_point_ids`. It shows the pending full-text endpoint explicitly,
without exposing IDs as prose or substituting the old EssayBank. The producer's
standalone essay body is not silently reinterpreted as this association.

Unresolved producer requirements for the original author: an approved
MCQ-to-essay association/full-text reader contract, and a versioned, evidenced
complete applicable-KP manifest. Until those are agreed and supplied, the UI
keeps the endpoint and complete denominator pending. Neither `weekKps` nor the
synthetic declared scope fills those gaps.

Windows validation (use the existing Windows queue and memory limits):

```sh
tools/dev/heavy --local ./gradlew :shared:domain:jvmTest :shared:client:jvmTest :androidApp:compileDebugKotlin
```

Mac-only incremental validation, no private corpus needed by these new cases:

```sh
tools/dev/heavy --local ./gradlew :server:app:test --tests com.coach.server.MainlineLadderTest --tests com.coach.server.SyntheticMainlineTest
tools/dev/heavy --local ./gradlew :shared:ui:jvmTest --tests com.coach.ui.LadderRuntimeAdapterTest
```

The new UI test runs the same producer JSON through actual Mainline week/answer,
HTTP, CourseHttp, selection, saved client state and the production screen. It
captures only new answer/scope/essay-pending behavior at 412×915 and 933×704.
The essay viewport is a component check; the server test separately verifies
the existing candidate association survives the actual answer response.
These commands and new screenshots need independent Mac verification; old layout
screenshots and their prior receipts do not validate this adapter.

PR150 remains draft until formal content permission, actual essay reader,
complete denominator, whole-issue review and device/release gates are satisfied.
