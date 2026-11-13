# ReaderLayout (partial)

This isolated Swift package contains a pose suggestion core and a synthetic
SwiftUI host. It does not
modify or depend on the unmerged iOS app in PR #119. `ReaderGeometry` is an
adapter boundary, not a source of hardware facts: callers must obtain hinge,
division, occlusion, container geometry, size class and face-up information
from supported system APIs. An unknown or not-yet-reported division is never
inferred from a device model, hinge angle or a fixed fold width.

This is **not** the requested completed layout module. The host uses
`onHingeChange` on iOS 27.1 and later to map reported system hinge status into
the pose suggestion. Until a status is reported, and for an unknown status,
it uses unavailable; the closed button and pose menu remain manual fixtures.
The host does not yet read system divisions or reserved regions, so its
automatic suggestion is not a fold-safe layout. `ArrangementView` layout,
reserved-region avoidance, event recording and real foldable state-continuity
verification remain to be implemented. Manual pose selection
must never override collision avoidance. A host must keep page, question,
selection and draft state above any view rebuilt by a pose change. StandBy
preservation needs separate device verification. Nothing here exposes reading
content on an additional display.

Run the pure logic tests with `swift test` in this directory. Open
`Host/ReaderLayoutHost.xcodeproj` to run the synthetic host; it includes no
real reading material or production integration. The PDF column and drawer are
containers, not real PDF parsing or navigation. The EPUB two-page threshold is
an application column minimum, not a device specification. Synthetic inputs
and non-Duo simulator results are not iPhone Duo evidence.

The host's pose menu uses synthetic choices only. Changing a choice recreates
the sample canvas while keeping its page, selection and note in the parent;
the sample drawer also keeps its question/source state across those changes.
This is a state-continuity fixture, not a fold-aware layout or a real source
lookup. Its UI tests exercise a 280-point container and accessibility text size
using launch arguments; those values are test fixtures, not device dimensions.
Another test-only launch mode changes the container width and text size while
the drawer is open; these controls are absent in the normal host.
The reader and drawer scroll separately when their content does not fit.
From the repository root, run its UI tests on an available iOS simulator with:

```sh
tools/dev/heavy --emulator bash ios/ReaderLayout/Host/run-ui-tests.sh <simulator-udid>
```
