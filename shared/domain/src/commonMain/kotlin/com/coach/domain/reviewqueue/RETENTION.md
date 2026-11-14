Checked review priorities
=========================

The starting algorithm in main `253d66c8` has ease-based intervals and no explicit
target retention parameter. Its intervals remain the reference schedule. This
policy calibrates that reference at 90%; it does not claim that the old code
contained a 90% setting or that learner recall has been measured at 90%.

The target for a checked three-star topic is 95%. One-star, two-star, unchecked
and unknown topics retain the 90% reference. Raising the policy target halves
the allowed forgetting probability (10% to 5%) for the top-priority subset,
without weakening mastery evidence or reducing lower-priority coverage.

A reference exponential forgetting curve converts the target into a real
interval: `floor(referenceDays * ln(target) / ln(0.90))`, with the existing
minimum of one whole day. A 20-day reference becomes 9 days at 95%; 23 becomes
11 days. This is a policy calibration, not a fitted memory model or an empirical
claim of 95% recall. At the one-day minimum the target cannot always be attained
by a whole-day schedule.

The actual `Projection.review` due date and interval change. Existing
`baseDueDay` and `baseIntervalDays` preserve the ease-based reference before
priority/exam overlays, so repeated replay, serialization and clock changes do
not cut the interval again. Postponing an exam restores the priority-adjusted
schedule. Removing checked priority restores the reference schedule. A due-only
look-again or snooze retains `intervalAnchorDay` before moving the deadline;
neither can invent an earlier interval origin. For configured topics a later
miss that advances the deadline supersedes the earlier snooze; a later explicit
snooze still wins.
A real spaced-review result starts a new interval. Unconfigured schedules keep
their existing due date outside the exam overlay, including look-again after
snoozing. Inside the exam window, unconfigured legacy topics retain their
explicit snooze after a miss, matching main; without a snooze the corrected
interval origin still keeps a new look-again due the next day, not overdue.
Original legacy due identities use the reference due date.

Input is an optional, explicit map in the existing authenticated
`configure_course` command / `course_configured` fact:

```json
{
  "timeZone": "Europe/London",
  "reviewPriorities": {
    "synthetic-topic": { "stars": 3, "reviewed": true }
  }
}
```

This declares a reviewed configuration supplied by the existing trusted course
configuration writer; it does not independently authenticate a teacher's review.
Only numeric 1..3 stars with boolean `reviewed: true` affect scheduling.
Malformed maps are rejected by LedgerStore before emitting facts; unchecked
entries cannot boost priority. A latest explicit map replaces the old map;
`{}` clears it, while a timezone-only configuration retains it. The field
contains no textbook/exam text. It is separate from `exam_weights_set` point
weights; a point weight of 3 or 300 never invents three stars. Existing materials
without this checked map remain unknown and use the reference schedule.

The review entry consumes the same projected deadlines. Within the normal
priority group, checked stars lead, but topic rounds offer one entry per topic
before taking a second entry from the same topic. Existing unseen/verified
variant filtering remains in force. The existing oldest-overdue protection
still takes precedence after 3 days, or 1 day in the configured final exam week.
Thus fresh three-star arrivals cannot permanently preempt one- and two-star
topics when eligible questions exist and the learner continues taking slots.

Projection version 15 invalidates cached version-14 projections, since the due
date policy and serialized review target now carry new meaning. No new ledger
event type, score/pass/solid rule, downloaded pool, device restoration protocol,
navigation flag, content file or deployment is introduced.

Synthetic checks are in `CheckedReviewDueRegressionTest` (also runnable against
the old public API as an old-due counterexample), `ReviewRetentionPolicyTest`,
`XvRetentionAnchorTest`, `ReviewAnchorTransitionsTest`,
`ReviewPriorityQueueTest`, and `ReviewPriorityScheduleTest`. Execution results
must be reported separately; source assertions alone are not a passing run.
