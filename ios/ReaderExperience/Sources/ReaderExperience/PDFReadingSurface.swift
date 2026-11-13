#if canImport(PDFKit)
import PDFKit

/// Copies before changing a page box, so the source document remains intact.
public enum PDFPageCrop {
    public static func copy(_ document: PDFDocument, pageIndex: Int, bounds: CGRect) -> PDFDocument? {
        guard let page = document.page(at: pageIndex), page.rotation == 0,
              !bounds.isNull, !bounds.isEmpty, page.bounds(for: .cropBox).contains(bounds),
              let data = document.dataRepresentation(), let copy = PDFDocument(data: data),
              let copiedPage = copy.page(at: pageIndex) else { return nil }
        copiedPage.setBounds(bounds, for: .cropBox)
        return copy
    }
}
#endif

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
    /// Supplied in PDF page coordinates by a verified layout adapter.
    public let focusBounds: CGRect?
    public init(document: PDFDocument, pageIndex: Int, selection: PDFSelection? = nil, focusBounds: CGRect? = nil) {
        self.document = document; self.pageIndex = pageIndex; self.selection = selection; self.focusBounds = focusBounds
    }
    public func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePage
        // Source highlighting belongs to this temporary view, never the
        // document being read or a persisted PDF annotation.
        let needsCopy = selection != nil || focusBounds != nil
        guard let originalPage = document.page(at: pageIndex),
              let displayedDocument = focusBounds.map({ PDFPageCrop.copy(document, pageIndex: pageIndex, bounds: $0) }) ?? (needsCopy ? document.dataRepresentation().flatMap(PDFDocument.init(data:)) : document),
              let displayedPage = displayedDocument.page(at: pageIndex) else { return view }
        if focusBounds != nil { view.displayBox = .cropBox }
        view.document = displayedDocument
        view.go(to: displayedPage)
        if let selection, displayedDocument !== document,
           document.page(at: pageIndex) != nil {
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
