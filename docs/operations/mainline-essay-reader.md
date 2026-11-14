The mainline reader consumes PR133's optional synthetic identity/scope extension
frozen at `3e08d0e93722122c9121c92915deb982b26e35cf` (independent producer review
receipt `01a1c797-65d3-757e-8343-742a82e2250c`). Base v1 MCQ calls remain supported.
Essay `reveal` is rejected: an essay must pass the course-bound reader instead.

The public fixture `testdata/question-ladder/synthetic-runtime-identity-v1.json`
is the frozen generator's UTF-8/LF output. SHA-256:
`ba65e6be38d39105f4eb9f0db2a84a5d20342edc603afe12e042b940a71e9d79`.
The UI additionally supplies an entirely invented longer answer to exercise
scrolling. Neither fixture is an approved production essay or exam source.

The new real service route is:

```text
POST /v1/courses/{course}/mainline/{week}/essay
Authorization: Bearer <paired-device-token>
X-Coach-Device: <paired-device-id>
Content-Type: application/json

{
  "itemId": "SYN-Q1@v2",
  "attemptId": "synthetic-<server-answer-receipt>",
  "modelAnswerId": "SYN-ANSWER-2",
  "rubricPointIds": ["SYN-RUBRIC-2A", "SYN-RUBRIC-2B"]
}
```

The application mounts it through its existing `mainlineRoutes`. It uses the
paired reader's authentication rules, rejects credentials in query parameters,
bounds the request body and checks authorization again in the ledger actor at
read time. Responses carry `Cache-Control: no-store`. Passing a synthetic
acknowledgement, capability list, course ID or essay ID in a request cannot
authorize a read. Course comes from the actual route and current source scope.

For explicit synthetic injection, the server first records an immutable answer
receipt in memory. It freezes the source question, linked essay version and
resource digest. A full-text read requires that receipt and exact ordered IDs,
the current course/question association, capability readiness, and unchanged
question/resource versions. Removed source access, revoked devices, missing
text, and wrong identities are rejected. Replaying an answer keeps its original
choice and association. It writes no grade, review or mastery events.

For existing production candidates, the reader checks the actual submitted
ledger attempt and frozen essay/step metadata, current candidate eligibility and
its hash. The original submission's essay and calculation fields now remain
frozen on replay even if current pack metadata changes. The producer has not
supplied an authorized production text-source contract, so this path returns
501 pending rather than substituting EssayBank text or treating synthetic
assets as released content. The default application does not inject a synthetic
runtime. No production permission/configuration is changed by this feature.

`CourseHttp.mainlineEssay` sends only the answer identity and validates course,
attempt, resource order, full body and rubric identities in the response. The
server binds the resource version and rejects changed resource/question digests.
Authorization failures and unavailable assets produce safe human messages.
IDs and error details do not become answer prose.

The actual question screen opens the full answer and corresponding rubric in a
separate page. It cancels any pending feedback reveal before leaving the answer
view. Asking uses the same source question; returning retains the essay scroll,
original answer, selected option, question scroll and ladder state. Reader state
stores only open/position/identity, under the existing paired-account/course
scope. Full text is not persisted to client SQLite and is fetched again on each
opening/reconstruction. A revoked account cannot restore a cached body.
Synthetic receipts are process-local: the demonstrated restart is a client
SQLite close/reopen with the isolated service running, not durable synthetic
receipt recovery across a service restart. Real attempt identity remains in
the existing ledger.

The optional scope mapping keeps these producer assertions separate:

| Input | Consumer |
| --- | --- |
| `course_id` | Exact synthetic source scope, also required by full-text reads. |
| `applicable_kps` | Declared KP count, including KPs with no candidate. Null is unknown. |
| `applicable_capabilities` | Declared `(KP, capability)` pair count, independent of the KP list. Null is unknown; repeated capability IDs on different KPs remain separate pairs. |
| `candidate_inventory_complete` | Only true can establish that a candidate is missing. False/null preserves unknown for empty candidate sets. |
| Active question refs | Candidate presence only; superseded refs are excluded and historical/recorded refs remain intact. |
| `essay_identity` | Exact model-answer ID, ordered rubric IDs and recorded MCQ links, with separately resolved links. No association is inferred. |

The checkpoint shows the declared synthetic KP/capability counts, missing KPs
and missing capabilities within a present KP. A missing candidate has an explicit
pending-teaching next step and a return to the week. No candidate-present state
claims semantic coverage, release or mastery. Outline rows remain navigation;
they do not supply missing denominators. Production completeness requires an
independently evidenced scope contract, which remains absent. Whether an
authorized production prerequisite catalog may include out-of-outline KPs also
remains unknown; existing withholding is retained.

Focused checks use only isolated synthetic sources and an ephemeral loopback
Netty service with the real routes, paired credentials and ledger actor:

```sh
tools/dev/heavy --local ./gradlew :shared:domain:jvmTest :shared:client:jvmTest :androidApp:compileDebugKotlin
tools/dev/heavy --local ./gradlew :server:app:test --tests com.coach.server.MainlineEssayRoutesTest --tests com.coach.server.MainlineLadderTest --tests com.coach.server.SyntheticMainlineTest
tools/dev/heavy --local ./gradlew :shared:ui:jvmTest --tests com.coach.ui.MainlineEssayFlowTest
```

The new UI evidence is `essay-reader-*` / `essay-scope-missing-*`, at 412×915
light and 933×704 dark. It covers the real full-answer opening, rubric tail,
question/ask return, client database reopening, missing text, wrong versions,
device revocation and the missing
scope point. A fresh session with an empty candidate inventory still shows the
declared denominators and missing points, with a usable return action. Old layout
and pending-text screenshots are not new reader evidence.
Physical devices, whole-issue acceptance, production content permission, actual
production essay assets/scope and release remain separate gates.
