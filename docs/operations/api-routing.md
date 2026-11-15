# Coach API routing and monthly accounting

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
Persisted OpenAI drafts retain an Anthropic reviewer. If both Anthropic API and
its subscription fallback are rate limited, grading keeps its existing immediate
OpenAI draft / Anthropic review fallback and notice; the two vendors stay distinct.
Existing evidence and grading gates still apply.

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

## Owner-authorized API access

On 2026-11-15 the owner waived the account-evidence prerequisite and authorized
using the existing keys under Coach's local monthly caps. No private admission
file or account attestation is required. A configured key can use its provider's
API immediately; a missing key still uses the subscription bridge.

The ledger measures Coach consumption in UTC calendar months. It does not measure
other applications in the shared organization or establish the provider's grant
expiry, remaining balance or billing behavior. No organization settings are changed.

## Conservative accounting

Before network I/O, an exclusive process lock reserves an upper bound calculated
from UTF-8 prompt size plus overhead and the maximum output tokens. Unsupported
context sizes/prices go to subscriptions. The bridge requests default service tier,
no tools, no explicit prompt-cache writes and no regional/Fast mode.

Atomic, fsynced `api-credit-ledger.json` is authoritative. Calendar summaries
`api-spend-YYYY-MM.json` report `spent_microusd`, `reserved_microusd`, and completed
`calls` per provider. One dollar is 1,000,000 microdollars. Coach's UTC monthly caps
are **Anthropic $150 and OpenAI $475**, including all unresolved reservations.
The ceiling also retains 5% of the nominal $200 / $500 grant; the stricter
$150 / $475 caps apply. Changing a key does not reset the provider budget.
Unresolved reservations from all prior months (including old ledger records)
continue to consume headroom until settled; completed spend resets each month.

Complete, model-matching usage settles the reservation at configured price;
timeouts, HTTP errors, cancellation, missing usage and stream interruption retain
the full bound across restarts. Unexpected over-bound usage closes the local month.
Do not delete a ledger to restore headroom. Log monthly progress as spent plus
unresolved reservations; neither is the account's authoritative remaining balance.

For a reservation left by a crashed process, first follow the existing maintenance
and idle gates to stop its owning service, then make a private backup of the ledger
and monthly summary. Only settle an identified `(cycle_id, month, token)` through
`CreditLedger.settle(provider, reservation, actual_microusd)` after the provider has
confirmed that request's final charge (an integer number of microdollars, including
zero only when confirmed). Without that evidence, retain the reservation; never
delete ledger records or reset the monthly limit to recover headroom.

## Streaming and fallback

Missing key, budget limit, 402/429 and API/transport errors use the same
vendor's existing subscription bridge. If the API already emitted partial text,
reset-aware readers receive `x_coach_reset` and **replace** that attempt's text.
Reader resets its parser/body deadline; the original total deadline still applies.
Chat emits a new snapshot retaining any completed preceding part. Old callers that
do not advertise reset support receive an explicit truncated terminal after partial
output, rather than concatenated answers. Cancellation closes the API stream and
retains its reservation.

Request logs record `transport=api` or `transport=subscription`, the actual model
in `served=`, and `ttft` in milliseconds. Use completed requests for production
routing and first-content latency checks; logs contain no source passage or key.

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
acceptance uses four private passages from `~/Coach/state/model-routing-20261115/` through an isolated server and
bridge, check the existing Reader format gate, actual Haiku served_by, and measured
first-content latency against the subscription path. Never import that existing
`run.py` as a helper: its top-level code starts calls. Keep source passages and
responses outside Git, comments and attachments. Production deployment still needs
the existing nonproduction gate, 15-minute idle observation, five-minute paired
teaching/quiz check and rollback. Do not interfere with TON-1630 validation.
