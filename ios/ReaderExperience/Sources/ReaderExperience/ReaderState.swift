import Foundation
import CoreGraphics

/// Values are supplied by the eventual PDF/Readium adapter; no page number is inferred.
public enum ReadingPosition: Codable, Equatable {
    case pdf(pageIndex: Int, columnIndex: Int?)
    case epub(locator: Data)
}

/// The caller supplies stable document and edition identifiers. A changed
/// edition never inherits notes until a separate anchor resolver verifies it.
public struct ReaderDocumentID: Codable, Equatable {
    public let document: String
    public let version: String
    public init(document: String, version: String) { self.document = document; self.version = version }
}

public enum ReaderNoteLocation: Codable, Equatable {
    case pdf(pageIndex: Int, exactText: String)
    case epub(locator: Data)

    /// Readium locator JSON can have different key ordering across launches.
    public static func epubLocator(_ data: Data) throws -> ReaderNoteLocation {
        let object = try JSONSerialization.jsonObject(with: data)
        return .epub(locator: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]))
    }
}

public struct ReaderNoteAnchor: Codable, Equatable {
    public let document: ReaderDocumentID
    public let location: ReaderNoteLocation
    public init(document: ReaderDocumentID, location: ReaderNoteLocation) {
        self.document = document; self.location = location
    }
}

public struct AnchoredReaderNote: Codable, Equatable {
    public let anchor: ReaderNoteAnchor
    public var text: String
    public init(anchor: ReaderNoteAnchor, text: String) { self.anchor = anchor; self.text = text }
}

/// Page-coordinate rectangles must come from a verified layout, not a width
/// heuristic. Reject a partial/outside box instead of focusing the wrong text.
public struct PDFPageFocus {
    public let crop: CGRect
    public let columns: [CGRect]
    public init?(pageBounds: CGRect, crop: CGRect, columns: [CGRect]) {
        guard !pageBounds.isNull, !pageBounds.isEmpty, !crop.isNull, !crop.isEmpty,
              pageBounds.contains(crop), !columns.isEmpty,
              columns.allSatisfy({ !$0.isNull && !$0.isEmpty && crop.contains($0) }) else { return nil }
        self.crop = crop; self.columns = columns
    }
    public func bounds(column: Int?) -> CGRect? {
        guard let column else { return crop }
        guard columns.indices.contains(column) else { return nil }
        return columns[column]
    }
}

public struct ReaderDraft: Codable, Equatable {
    public var position: ReadingPosition
    public var selectedQuestionID: String?
    public var answers: [String: String]
    public var notes: [String: String]
    /// Optional for decoding older drafts. Legacy unanchored notes are kept,
    /// but never silently treated as document-verified notes.
    public var anchoredNotes: [AnchoredReaderNote]?

    public init(position: ReadingPosition, selectedQuestionID: String? = nil,
                answers: [String: String] = [:], notes: [String: String] = [:],
                anchoredNotes: [AnchoredReaderNote]? = nil) {
        self.position = position
        self.selectedQuestionID = selectedQuestionID
        self.answers = answers
        self.notes = notes
        self.anchoredNotes = anchoredNotes
    }

    public func note(at anchor: ReaderNoteAnchor) -> String? {
        anchoredNotes?.first(where: { $0.anchor == anchor })?.text
    }
    public mutating func setNote(_ text: String, at anchor: ReaderNoteAnchor) {
        var values = anchoredNotes ?? []
        if let index = values.firstIndex(where: { $0.anchor == anchor }) { values[index].text = text }
        else { values.append(AnchoredReaderNote(anchor: anchor, text: text)) }
        anchoredNotes = values
    }
}

/// Storage belongs to the host, which must isolate it by account and book.
/// It must report errors rather than pretending a draft was saved.
public protocol ReaderDraftStore {
    func load() throws -> ReaderDraft?
    func save(_ draft: ReaderDraft) throws
}

public enum SourceResolution: Equatable {
    /// The adapter is responsible for verifying that the passage is the exact sentence.
    case verified(position: ReadingPosition, passage: String)
    case missing
    case changedOrAmbiguous
    case unavailable

    public var message: String? {
        switch self {
        case .verified: return nil
        case .missing: return "这一页没找到出处"
        case .changedOrAmbiguous: return "原句改过了，对不上"
        case .unavailable: return "暂时无法确认出处"
        }
    }
}

public protocol ReaderSourceProvider {
    func resolveSource(questionID: String) async -> SourceResolution
}

/// Transient drawer state is separate from the saved reading location and drafts.
public struct ReaderSession {
    public private(set) var draft: ReaderDraft
    public private(set) var source: SourceResolution?
    public private(set) var isQuestionListOpen = false

    public init(draft: ReaderDraft) { self.draft = draft }

    public mutating func setPosition(_ position: ReadingPosition) { draft.position = position }
    public mutating func setAnswer(_ answer: String, for questionID: String) { draft.answers[questionID] = answer }
    public mutating func setNote(_ note: String, for anchorID: String) { draft.notes[anchorID] = note }
    public mutating func openQuestions() { source = nil; isQuestionListOpen = true }
    public mutating func selectQuestion(_ id: String) { draft.selectedQuestionID = id }
    public mutating func showSource(_ result: SourceResolution) { source = result; isQuestionListOpen = false }
    public mutating func returnToQuestion() { source = nil; isQuestionListOpen = true }

    /// Layout columns are provided by the page analysis, not guessed from page width.
    public mutating func nextPDFColumn(columnCount: Int) {
        guard columnCount > 0, case let .pdf(pageIndex, current) = draft.position,
              let current, current >= 0, current < columnCount - 1 else { return }
        draft.position = .pdf(pageIndex: pageIndex, columnIndex: current + 1)
    }
}

/// Exact page-list lookup only: an absent mapping stays unknown.
public struct PrintedPageList {
    private let pages: [Data: String]
    public init(pages: [Data: String]) { self.pages = pages }
    public func label(for locator: Data) -> String? { pages[locator] }
}
