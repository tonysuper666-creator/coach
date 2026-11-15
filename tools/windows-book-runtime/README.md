# Trusted Windows book runtime inputs

This engineering slice connects PR262's ordinary loader to explicit build inputs.
PR264's consent/UI tests and PR270's auth cleanup remain separate drafts. This
does not authorize a release, install anything, or enable production explanations.

The objective is to let an explicitly authorized ordinary window use real
components. Acceptance requires independent approval of the trust anchor and
exact component bytes, correct packaged paths, and zero auth reads/launches when
trust is missing. Checks use repository code, public signature vectors, isolated
build outputs and compiler receipts; they never read a user's credentials/books.

## Trust and rebuild policy

The policy is **exact artifact SHA-256**. Every rebuild that changes even one byte
requires a new manifest and detached signature under the existing approved key.
Legacy .NET Framework `csc.exe` changes PE timestamps and MVIDs; these builds are
not byte reproducible. Source blob hash and compiler hash prove provenance, not
approval of different output bytes. There is no timestamp/MVID whitelist, binary
normalization, source-only authorization, automatic signing, or new trust root.
Reuse an immutable previously approved artifact, or approve the new exact bytes.

The packager needs all of the following from the existing approval process:

- Canonical X.509 DER Ed25519 public key, 44 bytes.
- Its lowercase SHA-256 fingerprint, confirmed independently in an approval record.
- UTF-8 manifest (at most 8192 bytes) containing exactly `contract`, `provider`,
  `model`, `notice`, `sha256`; the first four values match PR262's loader identity.
  `sha256` contains exactly `codex.exe`, `coach-iso.exe`, `coach-auth.exe` hashes.
- Detached Ed25519 signature, exactly 64 raw bytes, over the **exact manifest bytes**.
- Those three exact binaries, no links/reparse points.

A self-computed fingerprint is not an independent approval. This tool cannot
establish who approved a key. It checks the explicitly supplied pin and signature,
and rejects malformed, noncanonical, off-curve and all small-order public points
before invoking OpenSSL or reading components. The all-zero encoding is order 4;
`01` followed by 31 zero bytes is the identity. Both are rejected. A valid public
point alone still does not authorize an unsigned manifest.

Public point decoding follows [RFC 8032 section 5.1.3](https://www.rfc-editor.org/rfc/rfc8032.html#section-5.1.3).
The tests use only the published public key/signature/message from section 7.1
TEST 2, offline. No test key is embedded into the ordinary application.

## Commands and layout

Build the two helpers from a fresh owned directory containing
`source/CoachIso.cs` and `source/CoachAuthCommit.cs`. Supply the full repository
commit that those files were taken from:

```powershell
powershell.exe -NoProfile -File build-helpers.ps1 -Root D:/agent/OWNED-FRESH-DIR -SourceHead FULL_40_HEX_COMMIT
```

The script holds an existing Coach heavy slot, requires 4 GiB free, uses Idle
priority, and refuses existing outputs. It compiles production entry points only,
records exact source/compiler/output hashes and rebuild policy, and inventories
Codex binary metadata without executing it. It does not discover signing keys.
The receipt's commit label must be cross-checked against the recorded source blob
hashes; passing a label is not itself a proof of source provenance.

After the independent approval inputs exist, verify and stage them offline:

```sh
python3 tools/windows-book-runtime/assemble-runtime.py \
  --public-key APPROVED/key.der --approved-key-sha256 INDEPENDENT_APPROVED_SHA256 \
  --manifest APPROVED/manifest.json --signature APPROVED/manifest.sig \
  --codex EXACT/codex.exe --isolation EXACT/coach-iso.exe --auth-helper EXACT/coach-auth.exe \
  --stage NEW-NONEXISTING-STAGE
```

The staging tree is:

```text
classpath/windows-book-runtime-trust.der
resources/windows-book-runtime/manifest.json
resources/windows-book-runtime/manifest.sig
resources/windows-book-runtime/codex.exe
resources/windows-book-runtime/coach-iso.exe
resources/windows-book-runtime/coach-auth.exe
```

Use that tree explicitly in the normal Gradle invocation:

```sh
tools/dev/heavy ./gradlew :desktopApp:processResources :desktopApp:prepareAppResources \
  -Pcoach.windowsBookRuntimeDir=ABSOLUTE-STAGING-DIRECTORY \
  -Pcoach.windowsBookRuntimeKeySha256=INDEPENDENT_APPROVED_SHA256
```

These tasks do not create an installer or start components. An actual release
still requires the existing release, consent, device and cross-review gates.
On Windows, ensure `python3` and an Ed25519-capable OpenSSL are available, or pass
`-Pcoach.runtimePython=...` and `-Pcoach.runtimeOpenSsl=...`. No dependency is needed
when the two approval properties are absent. No PATH/key discovery enables trust.

`prepareWindowsBookRuntime` revalidates all inputs on every invocation. The DER
enters main classpath resources; the other five files enter
`appResourcesRootDir/windows/windows-book-runtime`, which Compose flattens into
`compose.application.resources.dir/windows-book-runtime` on Windows. This follows
[Compose's application resources contract](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html#adding-files-to-packaged-application).
It does not put Windows binaries into a Mac distribution.

With neither property, the generated tree is empty and the ordinary loader is
Unavailable. With just one property or failed verification, the build fails.
Before checking inputs, the task removes its generated runtime and the reserved
runtime under Compose's preparation output, including when Compose's Sync would
skip NO-SOURCE. `processResources` also removes a previously copied reserved DER
before its Copy action. These are owned build outputs, never installed/user data.
A failed build must not ship an old jar/distribution from a previous build.

## Checks and limits

```sh
tools/dev/heavy --local python3 -m unittest discover -s tools/windows-book-runtime/tests -v
tools/dev/heavy --local python3 tools/windows-book-runtime/tests/check_gradle_resources.py
tools/dev/heavy --local ./gradlew :desktopApp:test --tests '*WindowsBookRuntimeBuildResourcesTest'
tools/dev/heavy --local python3 tools/dev/winbox.py --ssh ./gradlew :desktopApp:test --tests '*WindowsBookRuntimeBuildResourcesTest'
```

The resource check seeds stale sentinel bytes only, removes them before any app
launch, rejects a zero key/zero signature through Gradle, and recovers to absent
inputs. The ordinary-loader tests use the actual main classpath, an isolated empty
SQLite library, and no injected runtime/root. They assert Unavailable for explain
and ask, zero auth resolution and zero transport receipts with absent trust.
They are headless loader checks, not a rendered Windows UI or an authorized
component startup. Previously passed discovery/consent/native tests are not rerun.

There is currently no independently approved key/signature input in this slice.
**Approved-input startup and positive packaged resource loading are unverified.**
Do not replace that missing observation with a generated key, test public root,
unsigned manifest, or a relaxed assertion. Real production explanation count: 0.
