# Isolated friend-path service

This Mac service supplies the existing Windows friend-path smoke with a real
registered book and an independent subscription bridge. It does not implement
the Windows steps or turn a successful HTTP explanation into a 7/7 result.
Keep the real book, selections, replies, credentials and screenshots outside Git.

## Prepare an authorized snapshot

Use an already authorized isolated source registration, its matching library,
and a server `installDist` built from the reviewed source. The source ledger is
opened read-only and backed up with SQLite. Source, approval and learning records
are preserved; only the copied device credentials and pairing code are cleared.
The official PDF importer verifies the PDF hash and page count and reuses its
explicit source binding. It does not grant approval or expand learned scope.

Set these paths locally; never paste private material into a PR or comment:

```sh
python3 tools/smoke/friend-service.py prepare \
  --source-data "$FRIEND_SOURCE_DATA" \
  --source-library "$FRIEND_SOURCE_LIBRARY" \
  --document "$FRIEND_DOCUMENT_ID" \
  --runtime "$FRIEND_INSTALL_DIST" \
  --runtime-revision "$FRIEND_BUILD_REVISION" \
  --bridge-python "$FRIEND_BRIDGE_PYTHON" \
  --java-home "$FRIEND_JAVA_HOME" \
  --tail-host "$FRIEND_TAILNET_IPV4"
```

The bridge Python needs `tools/bridge/requirements.txt`; Java needs JDK 21 and
the probe needs `pdftotext`. Build/test through `tools/dev/heavy`, observing the
current machine allocation. No build is performed by this service launcher.
The default private root is `~/Coach/state/ton1649-friend-service`. Preparation
requires a new directory. Do not use the production ledger as an input.

A whole-book PDF hash is not the identity of a chapter Markdown source. Compare
the catalog's `explainSourceId`, course, source-page range and actual page text.
`printedPageOffset` affects display only: source and block page numbers remain
physical PDF pages. The probe requires the selected quote to match both the PDF
page and the stored source; the server then enforces its existing visible and
learned scope. A visible source alone is not permission to explain every page.

## Start, pair and verify

From this checkout, use the daemon-supervised environment, not a background shell:

```sh
"$MULTICA_CLI_PATH" env start friend-service
"$MULTICA_CLI_PATH" env list --output json
python3 tools/smoke/friend-service.py pair --winbox
python3 tools/smoke/friend-service.py probe --selection-file "$FRIEND_SELECTION_FILE"
python3 tools/smoke/friend-service.py probe --kind example --selection-file "$FRIEND_SELECTION_FILE"
```

The daemon allocates a nonproduction server port; the loopback bridge takes the next port.
The declared lifetime is 24 hours. The server binds loopback and the configured
Tailscale address; the bridge binds loopback only. Inherited production paths,
API keys and service-manager settings are cleared. All server state and pack
directories are private to this instance. The bridge uses the existing Codex
subscription, exact model identity and no fallback, API or background batching.
`COACH_READER_MODEL` is permitted only with explicit test traffic and a private
loopback bridge; the normal reader default and vendor verification are retained.

The private selection JSON uses the existing request shape: `selection` and
`blocks`, with a block `id` of `<registered-pdf-sha256>:p<physical-page>:0` and its
`text`. Use an authorized real excerpt. Do not synthesize learning/approval events.
Probes fail unless a real reply contains text and citations to that exact block
and physical page. They also check authentication, bridge health, an unknown PDF
hash and an incorrect page. Output contains counts and timing; the reply stays
in `<kind>.private.json`, and a redacted receipt in `probe-<kind>-result.json`.

Pairing uses a fresh five-minute single-use code sent through SSH stdin. It
checks unauthenticated 401, successful pairing 200, replay 401 and authenticated
hello 200 from winbox. Windows credentials have an owner-only ACL and are saved
under `D:/agent/friend-path-service/client/pairing`; `result.json` is the receipt.
`connection.json` contains credentials and must remain private. Do not attach it.

## Private friend API mode

The snapshot/subscription mode above remains the default. For the native client
to import its own local book as a real friend, stop this isolated environment,
back up its config, runtime and SQLite database privately, and replace only its
`runtime/` with a current reviewed `:server:app:installDist` output. Keep the
isolated database, registrations and library; do not run `prepare` again or
change source visibility or learning records. Record the server build revision,
JAR SHA-256 and bridge source revision separately in the private config/receipt.

Set `friendApi: true` and `apiStateDir` in the private `config.json`. The state
path must resolve to the existing `~/Coach/state`, with its existing
`api-credit-ledger.json`. The same file contains owner cycles, monthly usage,
reservations and friend usage; a new empty ledger would reset the owner's cap.
Do not copy an old ledger back after requests. Compare its cycles/months and
pending reservations before the first request. Books, the service SQLite database
and bridge process state still stay under the isolated service root.

Create a service-root `friends.json`, owned by the current user with mode 0600:

```json
{"paused":true,"defaults":{"daily_calls":100,"daily_tokens":1000000}}
```

Start paused, then explicitly unpause for the authorized verification. Existing
provider monthly limits remain in force. Only the bridge process reads the
existing `coach-anthropic-api` Keychain item, using the same item as
`tools/bridge/run.sh`; no key is written to config, a file, argv, output or the
server JVM environment. It accepts only test reader requests with a friend ID
and device, and retains the shared gift API router, friend daily limits and
owner monthly accounting. It never falls back to a subscription CLI. The model
request uses the normal reader identity; actual API model selection and
`served_by` remain the shared router's responsibility.

Pair using a private file containing the friend name (never an argument):

```sh
python3 tools/smoke/friend-service.py pair --winbox --friend-name-file "$FRIEND_NAME_FILE"
```

The Windows helper requires the pair response to say `friend`, checks public
status 200, private hello 404, anonymous 401 and replay 401, and replaces only
the private seed credentials. Coordinate with the existing smoke owner before
replacing them. An already running native client needs its existing smoke owner
to load the new seed; this helper does not operate a desktop window. Real book
import, consent, OCR and screenshots stay on Windows. Use that native flow for
friend verification; the legacy `probe` is an owner snapshot probe and does not
validate a friend's local source contract. Synthetic fixtures do not accept a
real textbook or prove real API usage, revocation or cost reconciliation.

For the native Windows development client, set `COACH_URL` to the running
instance's tailnet URL and `COACH_CLIENT_DIR` to
`D:/agent/friend-path-service/client`. Also set `COACH_TOKEN_FILE` to an unused
path in that private directory, and `COACH_LIBRARY_DIR` to the test bookshelf.
Do not set acceptance replay switches unless the caller configures their
separate client identity. Open one client at a time through the existing Windows
heavy/interactive entry. The existing smoke worker creates its own client root:
its owner must connect pairing there rather than assume these credentials follow
it. This helper does not edit the smoke, launch a UI, consent to a book, mark it
learned or admit questions.

## Lifecycle and cleanup

`env list` reports readiness, expiration and the daemon log. An unauthenticated
hello returning 401 demonstrates the HTTP listener only; use pairing and the
real-selection probes to establish usability. Private child logs are
`logs/server.private.log`, `logs/bridge.private.log` and `bridge/logs/bridge.log`.
`running.json` records the supervisor PID, exact child PIDs, ports and start time.
The supervisor refuses a second instance of the same snapshot and exits if
either child fails. Daemon stop terminates its process group; the supervisor
also terminates and waits for its exact children. The daemon stops it at its TTL.

```sh
"$MULTICA_CLI_PATH" env stop friend-service
```

After stop, verify those exact PIDs and ports have gone. Never kill `multica` by
name. Archive the stopped Mac private root and the Windows
`D:/agent/friend-path-service` directory to their host's designated trash/staging
area when no longer needed. Do not remove the authorized source snapshot or the
real textbook. To reproduce from scratch, prepare a new snapshot and pair again.

Questions copied from a historical ledger are not evidence that this book has
an admitted, capability-bound question set. Empty private pack directories do
not remove historical database questions. Report real Q&A, content admission and
Windows steps 4–7 separately; never count missing questions as a passed step.

## Code checks

```sh
python3 -m unittest discover -s tools/smoke -p test_friend_service.py
tools/dev/heavy ./gradlew :server:app:test --tests com.coach.server.ReaderModelTest --tests com.coach.server.ReaderExplanationTest
```

The Python tests cover isolation boundaries and the adapter's explicit model
contract. The reader tests cover an honest Codex result, rejected vendor mismatch
and the existing explanation invariants. Live probes exercise the real model;
neither these tests nor pairing replaces the owner's Windows seven-step smoke.
