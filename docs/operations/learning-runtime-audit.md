# Learning runtime contract and behavior

Author-safe producer input checked on 2026-11-12: PR133
`3e08d0e93722122c9121c92915deb982b26e35cf`,
`docs/question-ladder/synthetic-runtime-v1.md`. Its optional identity/scope
extension remains the latest producer head. The older author-safety comparison
attachment `01a1c766-8746-79a2-8273-e9dbac5fb75c` describes earlier consumer
heads; its missing-adapter observations need the current consumer checks below.
No private candidate text or complete exam mapping is used here.

| Contract or behavior | Actual consumer | Executable evidence | Remaining production requirement |
| --- | --- | --- | --- |
| Capability distinct from teaching kind; unknown prerequisites | `SyntheticLadderRuntime`, `MainlineLadder`, `QuestionLadder.next` | `SyntheticLadderRuntimeTest`, `MainlineLadderTest`: independent capability gates, unknown stays withheld, candidate IDs cannot masquerade as prerequisite KP IDs | Approved immutable candidate pool and verified prerequisite catalog policy |
| Version aliases | `SyntheticLadderRuntime.parse/resolve`; recorded and resolved refs remain separate | `SyntheticLadderRuntimeTest`, `SyntheticTeachingIdentityTest`: reject cycles, dangling refs, version rollback and identity changes; superseded candidates stay out of supply | Approved production version/identity input; synthetic aliases grant no permission |
| Essay full text and rubric association | `Mainline.essay`, `MainlineEssayRoutes`, `CourseHttp.mainlineEssay`, `MainlineEssayReader` | `MainlineEssayRoutesTest`, `MainlineEssayHttpTest`, `MainlineEssayFlowTest`: real paired HTTP, server receipt, exact identity/version, revoke/missing cases; actual client SQLite close/reopen | Approved production text source/provider; current production read is pending/501 |
| Entire applicable KP and capability denominators | `LearningRuntimeState`, `LadderCheckpoint` | `SyntheticTeachingIdentityTest`, `LearningRuntimeStateTest`, `MainlineEssayFlowTest`: declared 4 KPs / 5 capability pairs, missing/unknown retained, empty inventory still visible; outline rows cannot supply the denominator | Independently evidenced complete production manifest; default runtime retains unknown |
| Two to four first tries and later back-reference | `QuestionLadder.next/record`, `Mainline.answer`'s `tryFirstRefs`, `MainlineScreen` | `MainlineAskFlowTest.twoFirstTriesLeadToTheirActualReferenceAndSurviveScreenReentry`: P1 → P2 → S1, reference to P2 displayed as number 2, screen reentry preserves receipt/session; `QuestionLadderTest` checks exclusion from mastery/review | Approved referenced questions and review evidence; no production references invented |
| Three distinct non-guessed correct responses | `QuestionLadder.learned`, `Projector` | `QuestionLadderTest`, `PendingMainlineScoreTest`: distinct fingerprints, failure/guess/direct explanation interrupt evidence; tryFirst and unchecked keys excluded | Real reviewed questions and real independent learner responses |
| Calculation steps to whole example | `QuestionLadder.next` consumes declared chain, step and complete-example IDs | New regression: missed step1 → correct different step1 variant → session serialization roundtrip → step2 → whole; original missed receipt stays false. Existing missing-step and prerequisite guards remain | Approved calculation association IDs; this domain roundtrip is separate from the real database reopening evidence above |
| Today entry and answer continuation | `TodayScreen.today-continue` → existing `CoachApp` route → `MainlineScreen` | `MainlineAskFlowTest.todayEntersInOneClickWithoutReadingAndReturnsToTheSameAnswer`: actual app click, no notes screen, submit, leave, one click back to the identical receipt and selected option | Full cross-device/user acceptance |
| Question drawer/companion rail and late replies | `MainlineScreen` inside `Desk`, `LadderQuestionAsk`, real `CourseHttp` | `MainlineAskFlowTest` at 412×915 light and 933×704 dark: open/send/source/close preserves answer and offset; delayed S1 reply is canceled and cannot appear in D1; D1 starts with an empty input | Device/system-back/accessibility acceptance and approved source retrieval |

The question UI tests use the existing invented orchard HTTP fixture; they
exercise the production screen and client, not the grading/release authority.
The essay route tests exercise an isolated authenticated service. Both remain
separate from production learning or content approval.

Three implementation defects were found and repaired during this audit:

1. The narrow question sheet read the default Inner metrics outside `Desk`.
   Its presentation now uses the page's real metrics; medium/inner windows
   retain the single companion rail.
2. Calculation prerequisites required every variant of a preceding step to
   have been independently correct, including immutable missed variants.
   Each declared step now needs at least one independently correct variant;
   every required step and the historical wrong response remain intact.
3. Direct explanation uses choice -1, which was rendered as a nonexistent
   `null` wrong option. Only real option indices now produce a selected-wrong
   heading; explanations for all unselected wrong options remain available.

Compatibility includes main `f81e9258` and its agent-run evidence isolation.
Projection version advances from main's 13 to 14 so a matching older cache
cannot retain the different merged projection semantics. Existing 10/11/12
upgrade checks remain, and version 13 is additionally rejected.

Run only the named synthetic UI/server/store suites, with the repository heavy
wrapper and the Windows queue/memory limits. The issue evidence bundle records
the exact head, full command, XML, red/green calculation regression, sanitized
selection traces and current interaction PNG dimensions/hashes. Prior image
receipts are not new behavior evidence.

Formal content permission, production full text, the complete production scope,
whole-issue acceptance, critical/device review and release remain pending.
PR150 stays draft; synthetic data never enters a formal pool.
