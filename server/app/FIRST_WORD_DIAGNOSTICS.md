# Offline first-word diagnosis

Each `COACH first_word` JSON line describes one Coach message attempt. Its `msg` and `rid` join the existing `COACH msg.end` log; `rid` also joins bridge logs when available; `parent_msg` links a fallback attempt to the original. The offsets `prepared_ms`, `bridge_start_ms`, `raw_first_ms`, `visible_first_ms` and `end_ms` use the same server monotonic clock, relative to receipt of the original WebSocket command. A missing event is `null`, including on cancellation, failure, empty output, or a cache hit. Progress frames are not visible body text.

For a single attempt, compare `prepared_ms` (receipt through preparation), `bridge_start_ms - prepared_ms` (post-preparation scheduling and request construction), `raw_first_ms - bridge_start_ms` (bridge invocation through first raw body), and `visible_first_ms - raw_first_ms` (filtering and waiting for visible body). Only calculate a difference when both endpoints exist. The timestamps are observation points, not proof of a particular cause within an interval. Cached replies do not invoke the bridge. Fallback uses a separate message and the same original receipt; its preparation is not repeated and remains null. Its non-streaming bridge response is observed only when the whole body returns, so `raw_first_ms` is not a streaming first-token measurement.

`bridge_queue_ms` and `bridge_ttft_ms` are independently supplied bridge terminal metrics, and the existing `msg.end ttft` comes from the bridge client. Client `WsFirstWords` uses its own clock. Do not subtract across these clocks or assume their intervals have identical boundaries. No question, answer, prompt, URL, exception text or raw chunks are written to the new trace. `msg.end` keeps its request ID and provenance fields for the existing production statistics join. Diagnostic reasons retain known codes and HTTP status codes; free-form HTTP bodies, exception messages and unrecognized reasons are redacted. Filter-drop logs do not include excerpts.

For a staged explanation, the first-word raw time excludes lesson chunks buffered before the first part finishes. The trace's request ID and bridge metrics, and the matching `msg.end` request ID, provenance and `ttft`, describe the first bridge invocation; the overall message status still reflects both parts.

Run the offline simulation (no real tutor or production service):

```sh
tools/dev/heavy ./gradlew :server:app:test --tests com.coach.server.FirstWordTraceTest --tests com.coach.server.ChatFlowTest --offline
```

The controllable-clock test distinguishes injected preparation, bridge and filtering delays; the simulated bridge exercises raw-before-visible, progress, empty output, cancellation and fallback through WebSocket routes.

For a staged reply, the trace and `msg.end` request ID, provenance and bridge metrics refer to the first upstream (the mistake explanation), whose output is displayed first. The parallel lesson upstream is buffered and is not treated as the first raw chunk; overall message status still reflects both parts.

To aggregate these lines offline, use `tools/dev/first_word_report.py`; its stage definitions, percentile rule, de-duplication and provenance limits are documented in `tools/dev/first_word_report.md`.
