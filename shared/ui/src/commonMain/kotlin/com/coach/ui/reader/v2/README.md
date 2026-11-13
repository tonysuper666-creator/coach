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

Available content width uses the current Android 700/1100dp boundaries: below
700 a single pane with a drawer, 700 through 1099 page/question panes, and 1100+
adds a page-directory pane. Fold-reserved book/tabletop regions take precedence.
The actual page viewport (after banners, bars, controls and hinge reservation)
decides readability independently of pane count. Reading first tries the full
trimmed page at viewport width. If its measured body would be below 16sp, it
enters column focus. Default pages, selected columns and source excerpts all
cap the body at 22sp, center the content and scroll vertically; focused columns
have at least 16dp horizontal margins. Large figures can pan without being cut.
Both font bounds follow system fontScale. Only explicit overview fits height
and may be a thumbnail. Reading starts at the first measured column's top;
page headers remain reachable by scrolling up. The Fold8 height-fit versus
readability tradeoff still needs the whole-product integration decision.

The native renderer must provide its measured smallest body font in full-page
units as `ReaderPageLayout.bodyFontSize`. Schema-v1 Docling output does not
provide this metric; do not fabricate it from OCR. Missing, nonpositive,
nonfinite, out-of-page or unrenderably small measurements take the same fallback:
expanded/Flex starts with the trimmed page; compact may focus the first column.
The full MediaBox's **longest rendered side is capped at 1200dp**, independently
of crop width, selection, source navigation and system fontScale. A selected
column/source is centered with 16dp margins and clipped to its region; it cannot
escape the cap by filling the pane. This is a conservative geometry bound, not
an inferred font size, a 16sp minimum or a 22sp readability guarantee. The plan's
`bodySizePx` stays null until a usable native measurement arrives. The fallback
may leave text small; real text and performance still require native validation.
Valid native measurements immediately resume 16..22sp with system fontScale.
All paths also bound the native renderer to a 16384px long side; metrics that
cannot fit that allocation at the requested upper font bound remain unknown.
Double scale arithmetic prevents tiny/huge metadata from overflowing before
that bound is applied. No infinite scale is used. The synthetic host
reports actual Android Paint size and baseline advance to tests independently
of the plan. Line-capacity checks use the measured viewport/baseline advance;
they do not assume a fixed font-to-line-height ratio or claim native reflow.

`ReaderSession` is hoisted outside layout branches. Local snapshots retain the
answer/note drafts, original column, selected anchor, pending confirmation and
return location. Snapshot storage belongs to the owner, in private account-scoped
storage; the fixture uses a separate synthetic-only preferences file. Source
geometry isn't persisted: a restored source drawer asks for native verification
again. This does not implement cross-device sync or durable study events.
Source navigation saves its originating drawer and logical focus as well as the
page/column. Returning from a page-margin question restores that question drawer
and its answer/list scroll position; closing it once more returns to the page.
Button and swipe use the same return operation and label. Rechecking a source
never overwrites its original return context. The context is an optional local
snapshot field; older snapshots retain the former standalone return behavior.
On RECHECK, the owner must use `selectedQuestionId` to obtain a **fresh** shared
and native resolution, construct that question with the new result, and call
`showSource` again. The screen does not start a resolver or reuse pre-restart
geometry. A failed recheck must remain an explicit unconfirmed result. Expanded
layouts clear a former compact QUESTIONS drawer and do not consume Back for it.

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

For the missing/invalid-metric regression plus the selected existing return,
source geometry, clipping, font/window and restart checks (without rebuilding
an APK or repeating the full previously accepted suite):

```powershell
powershell -NoProfile -File androidApp/src/test/reader-v2/check.ps1 -Offline -MissingMetrics
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
