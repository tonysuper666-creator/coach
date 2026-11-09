import Foundation

/// Values are supplied by the eventual PDF/Readium adapter; no page number is inferred.
public enum ReadingPosition: Codable, Equatable {
    case pdf(pageIndex: Int, columnIndex: Int?)
    case epub(locator: Data)
}

public struct ReaderDraft: Codable, Equatable {
    public var position: ReadingPosition
    public var selectedQuestionID: String?
    public var answers: [String: String]
    public var notes: [String: String]

    public init(position: ReadingPosition, selectedQuestionID: String? = nil,
                answers: [String: String] = [:], notes: [String: String] = [:]) {
        self.position = position
        self.selectedQuestionID = selectedQuestionID
        self.answers = answers
        self.notes = notes
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
