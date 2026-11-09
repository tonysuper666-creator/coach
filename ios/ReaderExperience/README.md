# ReaderExperience (independent work in progress)

This package is a provisional flat-screen interaction and state slice. It is
not connected to CoachApp, ReaderLayout, PDFKit, Readium, a service, or the
ledger. The SwiftUI preview uses synthetic text only. The eventual host must
provide a reading surface, verified source resolution, persistence isolated by
account and book, and adapters to the real shared locator and page-list format.
The package does not perform matching or infer printed page numbers. An unknown,
ambiguous or unavailable source must never be supplied as `verified`.

`ReaderDraft` is a serializable value; persistence is deliberately injected and
has not been wired into the view or tested across process restarts. Likewise,
the source preview is not yet a PDF/EPUB navigation or flash implementation.
No sync, mastery, real book performance, posture, or accessibility acceptance
is implied by this package. The app integration and all end-to-end gates remain
dependent on the upstream work.

From the repository root, run the local package tests through the build guard:

```
tools/dev/heavy --local swift test --package-path ios/ReaderExperience
```
