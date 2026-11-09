# ReaderLayout (partial)

This isolated Swift package contains a pure pose suggestion core. It does not
modify or depend on the unmerged iOS app in PR #119. `ReaderGeometry` is an
adapter boundary, not a source of hardware facts: callers must obtain hinge,
division, occlusion, container geometry, size class and face-up information
from supported system APIs. An unknown or not-yet-reported division is never
inferred from a device model, hinge angle or a fixed fold width.

This is **not** the requested completed layout module. Xcode 27.0 lacks the
27.1 APIs. The adapter, `ArrangementView` layout, reserved-region avoidance,
synthetic app host, event recording and state-continuity verification remain
to be implemented and built with the actual 27.1 SDK. Manual pose selection
must never override collision avoidance. A host must keep page, question,
selection and draft state above any view rebuilt by a pose change. StandBy
preservation needs separate device verification. Nothing here exposes reading
content on an additional display.

Run the pure logic tests with `swift test` in this directory. These tests use
synthetic inputs and are not iPhone Duo simulator evidence.
