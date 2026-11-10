## Minimal contract

TON-1400 adds claim-level references to the existing KnowledgePoint content and outline-replay contract. This is an independent domain/data/paired-reader adapter slice. It does not publish or revise any real course content.

~~~json
{
  "id": "invented-point",
  "sourceAnchors": [
    {"docId": "invented-book", "pdfPage": 4, "sourceKind": "textbook", "claimRef": "definition"},
    {"docId": "invented-slides", "pdfPage": 7, "pdfPageEnd": 9,
     "sourceKind": "slides", "claimRef": "comparison", "supportedSubpoint": "Compare the invented cases"}
  ]
}
~~~

- PDF page numbers are one based and inclusive. Omitted pdfPageEnd means the same single page; it never means a chapter start. Disjoint evidence uses separate entries, including when several entries support the same claim.
- docId is an exact identifier, not a path, title, inferred alias or authorization grant. Before a page request the paired ReaderHttp catalog must contain exactly one matching registered document, the same sourceKind (textbook/slides/reading), and the whole declared page range. Do not infer a slides registration from a textbook or from a week label.
- At least one nonblank claimRef or supportedSubpoint is required. claimRef is scoped to the knowledge point; both selectors, when provided, must match exactly. Labels and declarations do not prove semantic support. No item is automatically marked accepted, taught or mastered.
- A missing/null sourceAnchors field preserves the old anchors[] then sourceAnchor read path. Explicit empty or malformed new fields are authoritative and cannot revive a legacy chapter link. KnowledgePoint serialization preserves the distinction between absent and empty. Defaults do not add a new field to old serialized points.
- writeKnowledgeSources preserves unrelated JSON and any historical alias; it does not manufacture an earliest-page primary alias. CourseView exposes all new references on KpRow.sourceReferences and clears its legacy scalar jump/reference for the new format, including invalid/cleared revisions. Historical binaries that ignore the new field must not be used as claim-level consumers; no publication is performed by this PR.

## Actual consumption

~~~kotlin
val kp = courseView.kp(pointId) ?: return
val result = courseHttp.reader.readClaimSource(kp, claimRef = "comparison")
// ChooseSource: caller selects an explicitly returned source, never the first by default.
// ChoosePage: caller selects a page inside the retained inclusive range.
val page = courseHttp.reader.readClaimSource(kp, claimRef = "comparison",
    source = chosenSource, pdfPage = chosenPage)
~~~

readClaimSource consumes the replayed KpRow and existing paired reader endpoints. It binds the outline event's course to the CourseHttp reader, even if two courses register the same docId. It preserves distinct sources/ranges, rejects a source from another claim/course, validates the catalog and returned document/version/page, and returns Unconfirmed on missing mappings, invalid metadata, denied/unregistered sources, bounds failure, edition mismatch or missing text. An Opened result means a checked physical page was read; semantic support still requires independent content review. No new endpoints, file access, searches, AI calls or progress/ledger mutations are added.

The existing single-anchor page display remains readable through its original consumer. A chapter-only legacy link cannot satisfy a new claim-specific request. This does not accept or rewrite the outstanding private candidate items.

## Ownership and remaining integration

At checkout the base was main c6cf552. File lists were checked against open PR145 (TON-1446 navigation), PR134 (TON-1431 desktop reader), PR144 (TON-1464 timeout) and PR142 (TON-1466 scope). This PR changes domain Content/reader source contracts, client CourseView and its new reader adapter, a read-only course identity passed from CourseHttp to ReaderHttp, their tests and this document. It changes none of those PRs' files, the ReaderLibrary, HTTP request/auth behavior, navigation, desktop windows or VisibleScope.

The one remaining shared wiring point is the learning/reader source picker: pass the chosen claim, source and page to this adapter and handle ChooseSource/ChoosePage/Unconfirmed. UI navigation and the reader's overall connection remain with their current authors and the existing reader dependency gate; this domain slice does not bypass that gate or claim the full UI is connected.

Real slides docId registration, private content rewriting and semantic review remain on Mac. No private manifest, textbook text, page image, candidate or original answer is copied to Windows/Git/platform. Real textbook accuracy, 30 answers, first-open/page-turn performance, restart/device/production/installation gates and phase-two restrictions remain.

## Validation

All fixtures are invented. Domain tests cover old/new decoding, typed/raw round trips, exact per-claim selection, disjoint ranges, invalid fields and clearing/fallback behavior. Client tests replay real outline events into CourseView and use local synthetic HTTP through CourseHttp/ReaderHttp to check the selected document/page, denied and cross-course references, ambiguous choices, bounds, returned identity/version, and lack of write/AI requests. Run the shared domain/client JVM suites and Android compilation in the existing Windows slots. Server/Mac/UI/device tests are not replaced by these checks.
