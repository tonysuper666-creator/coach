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

readClaimSource consumes the replayed KpRow and existing paired reader endpoints. It binds the outline event's course to the CourseHttp reader, even if two courses register the same docId. It preserves distinct sources/ranges, rejects a source from another claim/course, validates the catalog and returned document/version/page, and returns Unconfirmed on missing mappings, invalid metadata, denied/unregistered sources, bounds failure, edition mismatch or missing text. An Opened result means a checked physical page was read; semantic support still requires independent content review. No new endpoints, file access, searches, AI calls or progress/ledger mutations are added. The claim dialog that shows that page does not POST reading progress for the visit, including later page turns in the dialog. Its system-back priority is 450, above Desk's margin sheet (300) and directory drawer (400). Citation focus still takes the back before the dialog closes.

The existing single-anchor page display remains readable through its original consumer. A chapter-only legacy link cannot satisfy a new claim-specific request. This does not accept or rewrite the outstanding private candidate items.

## Ownership and remaining integration

At checkout the base was main c6cf552. File lists were checked against open PR145 (TON-1446 navigation), PR134 (TON-1431 desktop reader), PR144 (TON-1464 timeout) and PR142 (TON-1466 scope). This PR changes domain Content/reader source contracts, client CourseView and its new reader adapter, a read-only course identity passed from CourseHttp to ReaderHttp, their tests and this document. It changes none of those PRs' files, the ReaderLibrary, HTTP request/auth behavior, navigation, desktop windows or VisibleScope.

At PR146 delivery, the remaining shared wiring point was the learning/reader source picker, passing the chosen claim, source and page to this adapter and handling ChooseSource/ChoosePage/Unconfirmed. The existing reader dependency gate still applies to overall integration.

The subsequent TON-1400 consumption change connects the existing Mainline answer citation, Study source action and V7 reading source action to ClaimSourceDialog. It offers the declared claim, then the source and inclusive page range as needed. Only Opened can enter PrivatePdfDocument. The selected catalog edition is carried through source/page selection with expectedVersion, and checked again before the document opens. Request state is scoped to the paired reader, outline row and question owner so closing or switching questions abandons pending results. Returning preserves the underlying question and answer. The existing legacy citation path remains available for rows without structured references.

Real slides docId registration, private content rewriting and semantic review remain on Mac. No private manifest, textbook text, page image, candidate or original answer is copied to Windows/Git/platform. Real textbook accuracy, 30 answers, first-open/page-turn performance, restart/device/production/installation gates and phase-two restrictions remain.

## Validation

All fixtures are invented. Domain tests cover old/new decoding, typed/raw round trips, exact per-claim selection, disjoint ranges, invalid fields and clearing/fallback behavior. Client tests replay real outline events into CourseView and use local synthetic HTTP through CourseHttp/ReaderHttp to check the selected document/page, denied and cross-course references, ambiguous choices, bounds, returned identity/version, and lack of write/AI requests. Run the shared domain/client JVM suites and Android compilation in the existing Windows slots. Server/Mac/UI/device tests are not replaced by these checks.

ClaimSourceConsumptionTest runs the product components against an isolated Ktor fixture through the real CourseHttp/ReaderHttp. Its invented page images exercise component display without Poppler. It covers the Mainline entry, claim/source/page choices, return and cancellation, course/registration errors, edition changes, revoked access, mismatched replies and a delayed response after changing the question owner. Request/response metadata goes to build/source-consumption and component captures to build/screens. Windows runs select only synthetic UI tests, through tools/dev/heavy --local and the existing Windows heavy slots at low priority. Private-material integration, Poppler, device and production acceptance remain separate gates.
