Coach device pairing (ADR-0004)

The server and Android build both read `coach.tailHost` from
`~/Coach/config/local.properties`. Keep that address local. Android denies
cleartext by default and allows only that host. The CIO clients also apply
Android's network policy before opening a socket; redirects are disabled.

Build `:server:app:installDist` and start `server/app/build/install/app/bin/app`
as usual. It creates the desktop credential in `~/Coach/data/device-token`
(or `COACH_TOKEN_FILE`) with mode 0600, repairs permissions on existing regular
files, and rejects symlinks. This file is the desktop's native-dependency-free
fallback to Keychain: local users who can read that file can manage devices.
Keep it private and out of backups shared with other people.

With the server running, in another terminal:

```sh
server/app/build/install/app/bin/app pair-code
server/app/build/install/app/bin/app devices
server/app/build/install/app/bin/app revoke DEVICE_ID
```

The CLI reads the desktop token from the file. Never pass it in command-line
arguments. Use the same `COACH_DATA_DIR`, `COACH_TOKEN_FILE`, `COACH_DEVICE_ID`
and `COACH_PORT` overrides for server and CLI if testing an isolated instance.
The CLI is the initial device-management interface; a desktop settings UI is
still deferred.

Open Tailscale on Android, start Coach, and enter the six digit code. It expires
at five minutes by wall clock, can be used once, and locks after five attempts.
Generating a new code invalidates the previous one. Each successful pairing
gets a server-generated device ID and a cryptographically random 256 bit token.
Only the SHA-256 digest enters SQLite. Paired phones cannot generate codes,
list devices, revoke devices, or replace the desktop credential.

Android encrypts the device ID and token with AES-256-GCM using a non-exportable
Android Keystore key. Only ciphertext and an IV enter private preferences;
backup is disabled. The server address is authenticated as GCM additional data.
Key invalidation or changing the server requires a new pairing. An HTTP 401
on reopening the app clears the invalid pairing and returns to code entry;
network unavailability keeps the saved pairing for offline use.

Integration: `PairingGate(store, serverUrl) { credentials -> ... }` gives the
screen its `PairedCredentials`. `KeystoreCredentials(context, serverUrl).load()`
can also supply them to `HttpLedgerTransport(serverUrl, deviceId, token)`.
`hello()` reports a rejected/revoked token as `DeviceAuthorizationException`.
All transports send `Authorization: Bearer ...` and `X-Coach-Device` headers,
including WebSocket upgrades and loopback. The sole unauthenticated route is
`POST /v1/pair` with JSON `code` and `name`, limited to 1024 bytes. Tokens and
codes in query parameters are rejected; they are never used as credentials.
Active WS connections recheck on every received command, every tutor frame,
every ledger push, and at least once per second. Revocation closes them with
policy violation status 1008.

Verification (synthetic data; no AI/bridge calls):

```sh
./gradlew :server:store:test :server:app:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --max-workers=6
python3 tools/dev/check-pairing.py
```

The second command owns and cleans up an isolated server and a read-only
`sable-api35` emulator on port 5586. It performs pairing over the allowlisted
Mac interface, reconstructs credentials in a fresh Android process, exercises
HTTP and WS, confirms preferences have no plaintext token, rejects other
cleartext hosts, and revokes the device through the CLI. It does not restart
Tailscale or touch the DeepTutor bridge. The JVM tests additionally verify
expiration, attempt limits, single use/restarts, SHA-256-only persistence,
HTTP/WS authorization failures, management restrictions and revocation of an
already connected tutor stream. Fold8 hardware acceptance remains separate.
