# Coach API routing and free-credit admission

`tools/bridge/model_routes.json` is the model, task, price and monthly-cap source.
The server keeps sending task names and vendor selectors to the local bridge.
The bridge selects the API model, preserving the draft/reviewer vendor boundary.

| Task | API | Subscription fallback |
| --- | --- | --- |
| Teaching, Reader selection/example, library explanation, source lookup | Anthropic Haiku 5.5, streamed | Existing Anthropic bridge |
| MCQ/constructed drafts, source repairs, grading drafts, journal summary | Anthropic Haiku 5.5 | Existing Anthropic bridge |
| Independent reviews and answer-key checks | OpenAI Terra | Existing OpenAI bridge |
| Malformed Terra review, once | OpenAI Sol | Existing OpenAI bridge |
| Planning / initial outline / outline revision task names | Anthropic Sonnet 5.5 | Existing Anthropic bridge |

Planning task names are reserved configuration: the present application constructs
outlines from its catalog; this change does not invent a new model-backed planner.
Legacy persisted OpenAI drafts retain an Anthropic reviewer. Grading failures no
longer reverse the two vendors. Existing evidence and grading gates still apply.

For recognized draft schemas and Reader's whole-answer gate, two consecutive Haiku
format failures permit one Sonnet attempt. Review schema failure permits one Sol
attempt; a substantive rejection is not retried until accepted. Requests carry
`X-Coach-Task` and `X-Coach-Format-Attempt`; the bridge logs content-free
`format_escalation` and `api_fallback` events.

## Credentials and startup

`tools/bridge/run.sh` reads macOS Keychain services `coach-anthropic-api` and
`coach-openai-api` into the bridge environment with shell tracing disabled. Never
run the security command directly in a captured terminal. Missing keys fall back
to subscriptions. The Anthropic key is shared with Sable: do not rotate it or alter
organization settings. All subscription CLI child environments strip both Coach
keys and the providers' standard API-key variables. Clients receive no key.

Install `tools/bridge/requirements.txt` into the bridge's Python environment before
using the startup script. Tests inject dummy keys and HTTP clients; they never
read Keychain. Directly launching the Python module does not read Keychain.

## Admission is closed until the account is demonstrably free-only

A key, a successful model request, and a local 95% ledger cannot establish this.
Other applications can spend the same grant between observations. Both providers'
grants expire on their subscription billing cycle, rather than the UTC calendar
month. They can continue into purchased credit / billing after a grant expires:
[OpenAI credits](https://developers.openai.com/api/docs/guides/chatgpt-api-credits),
[Anthropic credits](https://platform.claude.com/docs/en/about-claude/api-credits-for-subscribers).

Admission requires account evidence of **zero purchased balance, auto-recharge off,
no invoice billing, and provider-enforced refusal at zero**, plus actual grant-cycle
timestamps and current remaining balance. An observation of a successful charged
grant is insufficient. This change does not obtain, fabricate, or modify those
account facts. If any fact cannot be established, leave API admission closed.
Do not change shared organization billing to enable Coach.

An operator who has verified these facts may write a private, mode-0600
`~/Coach/state/api-credit-admission.json` containing one record per verified
provider. No example with a true attestation is installed automatically. The
required record fields are:

| Field | Meaning |
| --- | --- |
| `key_sha256` | SHA256 of the existing key, computed privately without printing the key |
| `free_only_verified`, `provider_stops_at_zero` | Both boolean true only with actual account evidence |
| `purchased_balance_usd`, `auto_recharge`, `invoiced` | Numeric zero, boolean false, boolean false |
| `grant_usd`, `remaining_usd` | Actual grant and remaining USD; grant at most 200 / 500 |
| `cycle_start`, `cycle_end` | Actual subscription-cycle UTC epoch seconds |
| `verified_at`, `valid_until` | Observation and expiry UTC epoch seconds; at most 24h old, within the grant cycle |
| `evidence` | Nonempty private reference to account evidence, never a secret or textbook text |

The file is shaped as `{"anthropic": {...}, "openai": {...}}`. Missing, corrupt,
expired, mismatched-key or paid-enabled records fall back before any API request.
Requests cannot start within 240 seconds of evidence/grant expiry. Account evidence
is a prerequisite, not a claim that local accounting controls provider billing.

## Conservative accounting

Before network I/O, an exclusive process lock reserves an upper bound calculated
from UTF-8 prompt size plus overhead and the maximum output tokens. Unsupported
context sizes/prices go to subscriptions. The bridge requests default service tier,
no tools, no explicit prompt-cache writes and no regional/Fast mode.

Atomic, fsynced `api-credit-ledger.json` is authoritative. Calendar summaries
`api-spend-YYYY-MM.json` report `spent_microusd`, `reserved_microusd`, and completed
`calls` per provider. One dollar is 1,000,000 microdollars. Coach's UTC monthly caps
are **Anthropic $150 and OpenAI $475**, including all unresolved reservations.
The grant-cycle ceiling additionally retains 5% of the grant, taking account of
observed external consumption. Refreshing evidence can only lower that cycle's
limit. Crossing a calendar boundary does not discard cycle reservations.

Complete, model-matching usage settles the reservation at configured price;
timeouts, HTTP errors, cancellation, missing usage and stream interruption retain
the full bound across restarts. Unexpected over-bound usage closes the cycle.
Do not delete a ledger to restore headroom. Log monthly progress as spent plus
unresolved reservations; neither is the account's authoritative remaining balance.

## Streaming and fallback

Missing admission/key, budget limit, 402/429 and API/transport errors use the same
vendor's existing subscription bridge. If the API already emitted partial text,
reset-aware readers receive `x_coach_reset` and **replace** that attempt's text.
Reader resets its parser/body deadline; the original total deadline still applies.
Chat emits a new snapshot retaining any completed preceding part. Old callers that
do not advertise reset support receive an explicit truncated terminal after partial
output, rather than concatenated answers. Cancellation closes the API stream and
retains its reservation.

## Batch and acceptance limits

These transports use standard streaming requests. Batch submission is not wired:
current workers and local bridge requests have synchronous deadlines and leases,
whereas provider Batch jobs can finish up to 24 hours later. A durable submit / poll /
resume contract is needed before Batch can safely replace these calls. The cost
helper's batch-price option is arithmetic only, not a Batch transport.

Offline checks:

```sh
python tools/bridge/tests/api_routing.py
python tools/bridge/tests/exam_protection.py
python tools/bridge/tests/fault_injection.py
python tools/bridge/tests/traffic_isolation.py
tools/dev/heavy ./gradlew :server:ai:test :server:app:test
```

Use the Python environment containing the pinned bridge dependencies. Real
acceptance remains separate: only after admission is verified, use four private
passages from `~/Coach/state/model-routing-20261115/` through an isolated server and
bridge, check the existing Reader format gate, actual Haiku served_by, and measured
first-content latency against the subscription path. Never import that existing
`run.py` as a helper: its top-level code starts calls. Keep source passages and
responses outside Git, comments and attachments. Production deployment still needs
the existing nonproduction gate, 15-minute idle observation, five-minute paired
teaching/quiz check and rollback. Do not interfere with TON-1630 validation.
