# Reader v2 Android preparation (TON-1426)

This is an isolated phase-one container, based on main. Production entrypoints,
PR125, anchor storage and the Docling importer are unchanged. The only host is
`androidApp/src/debug/.../reader/v2/ReaderV2FixtureActivity.kt`. Its pages, answers
and citations are explicitly synthetic; no server or course material is read.

The common container supports narrow column focus/overview, a bottom half-height
source drawer with a visible question behind it, a collapsible expanded rail,
book panes and a tabletop split outside runtime fold bounds. WindowManager's
pixel bounds are converted to content-local dp in the debug host; the screen
then subtracts its own toolbar height. A closed fold has no hinge feature and
uses the current window width. No model names, hinge angle guesses or hardware
resolution constants select a layout.

`ReaderSession` is hoisted outside layout branches. Local snapshots retain the
answer/note drafts, original column, selected anchor, pending confirmation and
return location. Snapshot storage belongs to the owner, in private account-scoped
storage; the fixture uses a separate synthetic-only preferences file. Source
geometry isn't persisted: a restored source drawer asks for native verification
again. This does not implement cross-device sync or durable study events.

`ReaderLayoutV1` reads PR127's schema-1 page JSON and checks the exact file digest,
page base and coordinate convention. Unreliable order/rotation/geometry uses the
original page. Full-width blocks expand a column's viewport to avoid cutting
figures or formulas. Unknown schemas/different file digests are rejected. The
native page slot must draw the unrotated **full MediaBox**; it must not pre-apply
CropBox or rotation. The page and highlight share one transform.

`NativeSourceTarget` is an integration boundary, not an anchor proof. Only supply
one after PR128's versioned index resolves AND the native extractor uniquely
locates that exact quote. Convert crop/rotation anchor coordinates to the full
renderer's coordinates first. Legacy page-only/paragraph/OCR offsets are not
sentence evidence. Wrong versions and ambiguous/changed/missing matches have
explicit errors and no highlight. No printed book page label is guessed here.

Windows synthetic acceptance, using the existing global two-slot mutexes and
Idle/low priority, with the repository's memory settings:

```powershell
powershell -NoProfile -File androidApp/src/test/reader-v2/check.ps1 -Offline
```

This runs only `:androidApp:testDebugUnitTest --tests com.coach.android.reader.v2.*`
and `:androidApp:assembleDebug`; it never runs shared UI corpus tests or server
tests. Synthetic screenshots go to `androidApp/build/screens/reader-v2`.
The debug activity is available via
`adb shell am start -n com.coach.android/.reader.v2.ReaderV2FixtureActivity`
on an authorized test device/emulator. It has no launcher entry and is absent
from release source sets. It is not the distributable phase-one product.

Still required after dependencies merge: production PDF/EPUB native rendering and
Readium locators, paper-page mapping, real paragraph/question associations and
mastery, real sentence/edition/font changes, flip/restart/cross-device gates,
real-book cold/reopen timings, Android emulator/OPPO and Fold8 layout evidence,
independent copy/layout review, and final same-version signed APK installation.
No local synthetic result substitutes for those gates; do not merge this draft
before TON-1423/1424/1400/1419 and the full task acceptance are ready.

WindowManager API basis: [Android foldable codelab](https://developer.android.com/codelabs/android-window-manager-dual-screen-foldables).
