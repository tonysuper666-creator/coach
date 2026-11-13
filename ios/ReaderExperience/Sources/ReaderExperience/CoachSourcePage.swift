import Foundation

/// The fields returned by the existing browsable-page endpoint. A page and
/// its text alone do not establish which sentence supports a question.
public struct CoachSourcePage: Decodable, Equatable {
    public let docId: String
    public let pdfPage: Int
    public let bookPage: Int?
    public let text: String

    public init(docId: String, pdfPage: Int, bookPage: Int?, text: String) {
        self.docId = docId; self.pdfPage = pdfPage; self.bookPage = bookPage; self.text = text
    }

    public func sourceWithoutSentenceAnchor() -> SourceResolution { .unavailable }
}
