# ReaderLayout (partial)

This isolated Swift package contains a pose suggestion core and a synthetic
SwiftUI host. It does not
modify or depend on the unmerged iOS app in PR #119. `ReaderGeometry` is an
adapter boundary, not a source of hardware facts: callers must obtain hinge,
division, occlusion, container geometry, size class and face-up information
from supported system APIs. An unknown or not-yet-reported division is never
inferred from a device model, hinge angle or a fixed fold width.

This is **not** the requested completed layout module. Xcode 27.0 lacks the
27.1 APIs. The adapter, `ArrangementView` layout, reserved-region avoidance,
event recording and real foldable state-continuity verification remain to be
implemented and built with the actual 27.1 SDK. Manual pose selection
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
