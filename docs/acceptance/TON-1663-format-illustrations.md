# TON-1663 — illustrations in common material imports

2026-11-16. Builds on the unified page illustration model merged in #337. Product decision 12 remains authoritative. This slice fills the model for DOCX, PPTX, HTML/HTM and DRM-free MOBI/AZW3. PDF/EPUB extraction and reader presentation remain TON-1665.

## Behavior

| Format | Saved illustration | Placement |
| --- | --- | --- |
| DOCX | Embedded DrawingML/VML raster pictures in body paragraphs and tables | Original run position mapped onto the saved page's UTF-16 indices |
| PPTX | One rendered PNG of each complete slide, including text, shapes and pictures | Anchor 0, before the slide's unchanged extracted text and notes |
| HTML/HTM | Embedded base64 images and local assets beneath the selected HTML directory | Image element position, with alt/title as optional caption |
| MOBI/AZW3 without DRM | Referenced internal image records | Image element position within the existing chapter/page split |

The authoritative page strings are unchanged. Pictures do not insert placeholders into text, alter pagination or move existing citations. Existing #337 persistence stores bytes by content hash within the book and reopens the same anchors, dimensions and captions without the original import file.

PPT rendering uses Apache POI 5.5.1 and installed system fonts. Before rendering, runs whose effective font cannot display their text switch to Java's logical SansSerif only when it covers the entire run. The change affects the in-memory rendering copy, never saved page text. This avoids POI's FAMILY fallback being overridden by its FONT attribute ([Java Font attribute precedence](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/Font.html)). The self-authored mixed-language slide was visually inspected; regression checks cover text orientation and an asymmetric picture. This is not a promise of pixel-identical PowerPoint rendering for every chart, SmartArt, animation or font. Legacy text-only exports without any shape geometry/layout are reconstructed as a text slide and explicitly captioned as reconstructed. Slide text/notes remain available independently.

## Bounds and local access

Supported raster payloads are PNG, JPEG and GIF (first frame); unsupported embedded image formats return an explanation. Images are bounded to 16 MiB each, 64 MiB total, 1,000 images, 8,000 pixels per edge and 16 million pixels. Dimensions are read before decode. PPT is additionally limited to 200 slides and a longest rendered edge of 1,600 pixels, with cancellation/progress checks between slides. Cancellation does not save a catalog entry.

HTML import never fetches remote URLs. Percent-decoded traversal and symlinks outside the selected directory are rejected; local assets are snapshotted on import. PPT external relationships are stripped before rendering, and archive/XML bounds and external-entity restrictions remain in force. DOCX header/footer/footnote pictures and floating visual layout are not covered by the body/table extraction. Kindle support retains the existing supported unencrypted containers; it does not remove DRM.

## Verification

```sh
tools/dev/heavy ./gradlew :desktopApp:test --console=plain
python3 tools/dev/update-poi-notices.py
```

Final Windows full desktop gate exited 0 on implementation `dc221382351b4d420d8d893b7c70c96e4bc727b7`: **437 tests, 430 executed, 7 existing opt-in skips, zero failures/errors**. Job `heavy-80564bce65ea4882ba413b055c26ad41`; Gradle 428.242 seconds, wall 445.61 seconds. No test was deleted, newly skipped or relaxed. The seven pre-existing opt-in skips are three native import/window tests and four live reader/server tests; this ordinary suite does not claim their real-service acceptance.

The final focused Windows gate (`--tests FormatBookIllustrationsTest --tests MultiformatBookTest`) exited 0: 7 tests, zero failures/errors/skips. The mixed-language slide PNG was also opened and visually checked for upright English/Chinese text, the shape and the asymmetric picture. This is a rendered import asset, not reader UI acceptance.

The four new tests cover the four flow formats (plus AZW3), exact page text and surrogate-aware UTF-16 anchors, repeated-byte hash reuse, deletion of originals and reopening, source resolution, slide pixels/orientation, HTML asset boundaries, cancellation and oversized image metadata. Fixtures and their generator are committed under `desktopApp/src/test/resources/books/illustrations`; all are self-authored, with no real study material.

The license generator's default is a dry run. It reads notices from the Maven artifact versions recorded from this build's dependency report and writes only with `--write`; update the version list when dependencies change. Thirteen dependencies contribute 26 packaged license/notice files; curvesapi is BSD-3-Clause and the others Apache-2.0. SparseBitSet's artifact omits license text, so its declared Apache-2.0 license and source attribution are supplied explicitly.

## Windows package measurement

Two isolated Windows package builds exited 0, using the same JDK 21, Gradle cache, version 1.0.1 and packaging command (`tools/dev/package-windows.ps1`, under the heavy gate and Windows named slot). No MSI was installed.

| Build | Commit | MSI bytes | SHA-256 |
| --- | --- | ---: | --- |
| Latest main before this change | `13c67894` | 117,145,686 | `afb82e0a2db7da9fb4c91cd80528e88aed502454745fef59029edd19da7feb42` |
| Import illustrations and font fallback | `dc221382` | 134,661,460 | `8ce2528eb349a8d321d93667e4a5cfd23e72dbe34a6c2c761a880d92312eb541` |

Increase: **17,515,774 bytes (17.52 MB, 14.95%)**. Both packages report `NotSigned`. The final acceptance-document commit adds no runtime files or dependencies beyond the measured implementation.

This measures package construction and size, not installation on a clean OS. No account, paired production service, real textbook or other application's window was accessed. This slice does not resolve the earlier 7/8 native real-subscription result, upgrade the paired service, establish clean Win10/11 compatibility, or validate final MSI installation. TON-1663 remains open for that work.
