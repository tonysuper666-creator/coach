# TON-1291 review scheduling and acceptance

The normal ledger schedule remains recoverable when an exam is postponed or cleared.
The shared due queue and offline-pack prioritization apply the exam overlay for the
current course logical day, even when no event arrives overnight. The course clock
uses the latest explicit `course_configured.timeZone`, then the loaded outline's
timezone, then Europe/London; its day starts at 04:00, including DST transitions.

For `d = examDay - today` in 0..7, cap the normal interval at
`max(1, floor((d - 1) / 2))`. Bring the normal due date forward from its original
interval anchor, at latest the day before the exam. Explicit snoozing remains
effective. Outside 0..7 or without `exam_set`, use the normal schedule. Completed
reviews stay out of the queue for the remainder of that logical day. The exam
panel and queue share `exam_set` / `CourseView.examDay`.

Review practice uses an independent persisted REVIEW thread and RECALL cards.
Entering it requests questions directly, resumes a pending group across reconnect
or server restart, and leaves the ordinary learning thread and Continue position
intact. Pool replenishment recovers this mode from the opening ledger fact.

## Local gates

Use Java 17 and the configured Android SDK. All Gradle work uses the heavy wrapper:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
tools/dev/heavy ./gradlew \
  :shared:domain:jvmTest :shared:client:jvmTest :shared:ui:jvmTest \
  :server:core:test :server:store:test :server:app:test :server:app:installDist \
  :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest \
  -PcoachApplicationId=com.coach.android.ton1291 -Pcoach.port=19291 \
  -Pcoach.course=ton1291-synthetic-review --console=plain
python3 tools/dev/check-review-copy.py
```

For the red comparison, create a disposable worktree at main `d715c87`, copy only
`shared/client/src/commonTest/kotlin/com/coach/client/ReviewSchedulingTest.kt` from
this branch into that worktree, and run there:

```sh
tools/dev/heavy ./gradlew :shared:client:jvmTest \
  --tests com.coach.client.ReviewSchedulingTest --console=plain
```

The client tests cover ledger/queue agreement, eight versus seven days, overnight
compression without events, postponement, unknown date, timezone/day boundaries,
empty ledgers, same-day completion and snoozing. Domain tests also cover DST and
idempotent restoration. Core tests cover review grading and replenishment after
restart. Shared UI tests exercise phone/desktop viewports and write synthetic
screenshots under `shared/ui/build/screens`; these are JVM images, not emulator
evidence.

## Emulator gate (required before acceptance)

Build the two APKs with the command above. Run the following only while entitled
to the shared emulator lane; never bypass or terminate another task's owner:

```sh
tools/dev/heavy --emulator python3 tools/dev/check-review-copy.py \
  --android emulator-5554 --screenshots ./build/ton1291-emulator
```

Replace the serial with the running **coach_1128_c70_r** emulator's serial. The
harness finds adb through `ANDROID_SDK_ROOT`, `ANDROID_HOME`, the local SDK or PATH,
and refuses physical devices and other AVD names. It installs only the card
package, backs up production SQLite through a readonly connection, cancels queued
jobs only in the copy, and seeds a synthetic course. The copy server uses an
ephemeral port and a synthetic bridge. Production processes, protected ports and
shared configuration remain untouched. Temporary credentials, database and logs
are deleted on exit. Use `--attachment` to deliver exported images, not local paths.

The Android check opens Today (1 due knowledge point), its queue (1), then practice
(three synthetic questions without a lecture). The protocol check restarts the
copy server, recovers that pending group and submits three correct answers. A
second Android pass checks Today and queue are empty. Screenshots and instrument
results are exported; answer submission in this harness is through WebSocket,
not Android taps. An interactive answering check remains useful for device release.

## Integration and device handoff

Main `9ea6b51` includes TON-1247's mock exam flow. The merged Review page retains
its mock entry alongside the live due queue; CoachApp preserves mock/records/week
navigation and resets review mode when opening ordinary learning. A CoachApp-level
regression checks mock entry/back, direct review entry and returning to ordinary
learning. This is host UI coverage; emulator evidence must be collected on the
merged head, replacing the earlier head's screenshots and protocol results.

PR #91 adds the exam panel: preserve its shared exam fact and the queue's live
course clock when resolving CoachApp/Today overlaps. PR #57 defaults Review to
mistakes: retain a visible due-queue entry and pass review mode only for that
entry, preserving its mistake flow. Neither PR is folded wholesale into this work.

For TON-1254 device/release acceptance: use the approved isolated server and card
package; open Today → due queue → Start; check the displayed count against the
ledger, answer a group and verify it leaves today's queue. Reopen mid-group to
confirm resume; switch to Continue and confirm its position is unchanged. Check
unknown exam date, the eighth/seventh-day boundary and the course's 04:00 boundary
on Android and desktop. Real-device/prod acceptance is not claimed by local gates.
