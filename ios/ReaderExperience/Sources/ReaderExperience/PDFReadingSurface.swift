#if canImport(UIKit)
import SwiftUI
import PDFKit

/// The supplied page index is a hint, never proof that the sentence exists.
public final class PDFSourceMatcher {
    public let document: PDFDocument
    public init(document: PDFDocument) { self.document = document }

    public func selection(pageIndex: Int, sentence: String) -> (SourceResolution, PDFSelection?) {
        guard !sentence.isEmpty, let page = document.page(at: pageIndex) else { return (.unavailable, nil) }
        let matches = document.findString(sentence, withOptions: []).filter { selection in
            selection.pages.count == 1 && selection.pages.first === page && selection.string == sentence
        }
        guard !matches.isEmpty else { return (.missing, nil) }
        guard matches.count == 1, let match = matches.first, !match.bounds(for: page).isEmpty else {
            return (.changedOrAmbiguous, nil)
        }
        return (.verified(position: .pdf(pageIndex: pageIndex, columnIndex: nil), passage: sentence), match)
    }
}

/// Separate PDFView instances can show the reading position and the source
/// drawer without moving the reader's original view.
public struct PDFReadingSurface: UIViewRepresentable {
    public let document: PDFDocument
    public let pageIndex: Int
    public let selection: PDFSelection?
    public init(document: PDFDocument, pageIndex: Int, selection: PDFSelection? = nil) {
        self.document = document; self.pageIndex = pageIndex; self.selection = selection
    }
    public func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePage
        // Source highlighting belongs to this temporary view, never the
        // document being read or a persisted PDF annotation.
        let displayedDocument = selection.flatMap { _ in document.dataRepresentation().flatMap(PDFDocument.init(data:)) } ?? document
        view.document = displayedDocument
        if let page = displayedDocument.page(at: pageIndex) { view.go(to: page) }
        if let selection, displayedDocument !== document,
           let originalPage = document.page(at: pageIndex),
           let displayedPage = displayedDocument.page(at: pageIndex) {
            let bounds = selection.bounds(for: originalPage)
            let annotation = PDFAnnotation(bounds: bounds, forType: .highlight, withProperties: nil)
            annotation.color = UIColor.systemYellow.withAlphaComponent(0.6)
            annotation.quadrilateralPoints = [
                NSValue(cgPoint: CGPoint(x: 0, y: bounds.height)),
                NSValue(cgPoint: CGPoint(x: bounds.width, y: bounds.height)),
                NSValue(cgPoint: CGPoint(x: 0, y: 0)),
                NSValue(cgPoint: CGPoint(x: bounds.width, y: 0))
            ]
            displayedPage.addAnnotation(annotation)
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) { displayedPage.removeAnnotation(annotation) }
        }
        return view
    }
    public func updateUIView(_ view: PDFView, context: Context) {}
}
#endif
