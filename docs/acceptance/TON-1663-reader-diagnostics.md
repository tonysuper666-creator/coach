# TON-1663 — reader refusal diagnosis and native import verification

2026-11-16. This follows merged #339 (automatic personal source registration) and #343 (format illustrations). Product decision 12 and the UTF-16 source contract are unchanged.

## Finding and change

The earlier PNG/dark native-window run reached the subscription model and ended with `reader.end status=format` after 8,127 ms. The seven preceding requests completed. That establishes a reader response format/citation refusal, not an OCR import or theme failure. The old log cannot identify the particular failed rule; neither source text nor rejected model output was retained. An exact historical cause is therefore **not established**.

Reader refusals now log a fixed reason code, validated request ID, bounded attempt number and test/user classification. Codes distinguish stream JSON, payload size/JSON, root/paragraph structure, paragraph text, anchors, duplicated anchors, invented page locators and citation/quotation validation. No source text, model answer, book path, exception message or credentials are logged. Every existing rejection condition and the existing Haiku-only retry policy remain unchanged.

## Verification

The focused server gate exited 0: **44 tests, no failures/errors**.

```sh
tools/dev/heavy ./gradlew :server:app:test \
  --tests ReaderExplanationTest --tests ReaderTimingTest \
  --tests LibraryExplainTest --tests ReaderTest :server:app:installDist
```

The added regression verifies distinct fixed refusal reasons for malformed, oversized, structurally invalid, unknown-anchor, invented-page and fabricated-quotation answers. Valid and insufficient-evidence answers keep their original behavior. Existing tests were not deleted, skipped or relaxed.

The fixture export and real-subscription native-window gates both exited 0:

```sh
tools/dev/heavy ./gradlew :desktopApp:test --tests SavedBookBindingTest -Pcoach.nativeOcr=true
tools/dev/heavy python3 tools/reader/check_local_book_live.py \
  --bridge-python <existing-bridge-venv-python> \
  --output <new-private-evidence-directory> --automatic --native-windows
```

**8/8 passed**: TXT, DOCX, PPTX and PNG OCR, each in light and dark themes. Windows job `heavy-94224f5e8fd34916af875aa64229c763` ran the actual Coach windows. Each imported a self-authored file, granted per-book consent, selected its saved text, automatically registered the source, obtained a real subscription explanation and clicked its citation back to the exact saved UTF-16 passage. Both `ImportedBookLiveWindowTest` and `ImportedBookWireTest` passed. All eight AI calls were served by `gpt-6.1-sol`, each with one physical call; model durations were 6,826–8,921 ms. No refusal occurred, including PNG/dark.

The runner used an isolated Mac server/database and private Windows loopback tunnel. It removed its connection file and stopped its server, bridge and tunnel before returning. Only committed, self-authored fixtures were used. This is **not** verification of the existing paired production service on port 18791. The prior intermittent refusal has not been reproduced or proven eliminated; fixed diagnostic codes make a recurrence distinguishable.

## Remaining acceptance

The paired service upgrade needs the existing production gates: independent acceptance plus cross-review, a frozen candidate and rollback configuration, nonproduction acceptance, at least 15 minutes without a user request, held admission fencing through shutdown, a private consistent backup, and paired-client/lecture/quiz checks within five minutes or automatic code/config rollback. Preserve all database writes during rollback. The custom reader library and every existing environment setting must survive; the general-purpose installer rebuilds the environment and must not be used for this upgrade.

Use only self-authored materials for this issue's production checks. Clean Win10/11 and final MSI installation remain unverified. Merged illustration support does not remove these requirements. TON-1663 is not complete.
