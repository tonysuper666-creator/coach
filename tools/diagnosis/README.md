# Weak topic diagnosis prototype

Run `python3 tools/diagnosis/diagnose.py <read-only-ledger-copy> <course-id> [--now <ISO-8601>]`.
It reads event payloads in memory and writes aggregate JSON only. It never emits answers, question text, or raw feedback.

Eligibility follows the current `Projector`: submitted, non-voided, non-hinted, first attempt for the KP/question pair, fingerprint not seen within 14 days, policy version 1, supported format, non-legacy origin, verified answer key, and exact or reviewed grading. Quick threads are excluded. A pass is score/max >= .8. Mastery is filtered using the current three distinct-fingerprint passed tail, the solid threshold (two strict-context passes on different days, one constructed, mean >= .85, latest strict-context pass), manual `MARK_PASSED`, or `legacy_passed`. These checks intentionally favor conservative exclusion.

The score is a transparent descriptive ranking: 50% error share, 20% recency (1 for the latest answer and decaying linearly to 0 at 30 days), 10% mean effort scaled to 20 minutes, and 20% exam weight scaled to 10. Per-KP `exam_weights_set` values override the fallback week weights (★★★=3, ★★=2, otherwise 0). Sorting ties resolve by error share, exam weight, then KP id. Weekly lists and the course list contain up to three unmastered/needs-repair topics with evidence. Unseen topics are omitted.

Cause labels are emitted only when grader feedback explicitly contains one of the recognized `错因：概念/计算/定义/应用` tags; everything else is `unclassified`. The prototype does not infer a cause from private answer text. Production feedback may not carry these tags, so that signal can remain unavailable. The prototype is not a calibrated predictive model; low counts must be presented with their evidence count and confidence limits.

Synthetic tests: `python3 -m unittest` from this directory.
