# Coach development

Run heavy work through `tools/dev/heavy`; do not invoke Gradle directly.

```sh
tools/dev/heavy ./gradlew :androidApp:assembleDebug :shared:domain:jvmTest :shared:protocol:jvmTest
tools/dev/heavy ./gradlew :server:app:test
tools/dev/heavy --emulator bash -c './gradlew :androidApp:assembleDebugAndroidTest && python3 tools/dev/check-android.py'
```

Explicit Android/shared Gradle tasks default to `ssh winbox`, with two remote
slots and independent job worktrees. Current uncommitted source is included.
APKs, build reports and test XML return to their normal repository paths;
transport logs and the exact snapshot SHA are in `.coach-winbox/<job>/`.
Remote memory/queue failure (75) or SSH unavailability (255) automatically uses
the existing Mac heavy lock. A Gradle failure is returned to the caller.

Keep server tasks (including mixed server/shared commands), emulator/device
tasks, desktop packaging, macOS/native tasks, ambiguous aggregate selectors
such as `test`, scripts and commands needing local paths/environment on Mac.
Use `--local` when that dependency is known only to the caller.

Never reboot winbox, change system settings, touch the user's accounts,
trading applications/browser or desktop session. All build processes run at
low priority. Job worktrees/bundles go into `D:\agent\_trash` before removal
from the active workspace. Do not run the legacy single-checkout winbox script
alongside the new runner; it does not share this runner's two-slot limit.

See `docs/operations/winbox-builds.md` for options and verification.
