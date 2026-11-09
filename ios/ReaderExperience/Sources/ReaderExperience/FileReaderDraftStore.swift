import Foundation

/// An explicitly scoped, local draft. This store is not a sync engine or ledger.
/// A caller must load before saving and must surface conflicts to the reader.
public final class FileReaderDraftStore: ReaderDraftStore {
    public enum StoreError: Error, Equatable {
        case notLoaded, conflict, wrongScope, unsupportedVersion
    }

    private struct Envelope: Codable {
        let version: Int
        let scope: String
        let revision: UUID
        let draft: ReaderDraft
    }

    private let url: URL
    private let scope: String
    private var loaded = false
    private var revision: UUID?

    public init(url: URL, scope: String) {
        self.url = url
        self.scope = scope
    }

    private func read() throws -> Envelope? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let envelope = try JSONDecoder().decode(Envelope.self, from: Data(contentsOf: url))
        guard envelope.version == 1 else { throw StoreError.unsupportedVersion }
        guard envelope.scope == scope else { throw StoreError.wrongScope }
        return envelope
    }

    public func load() throws -> ReaderDraft? {
        let envelope = try read()
        revision = envelope?.revision
        loaded = true
        return envelope?.draft
    }

    public func save(_ draft: ReaderDraft) throws {
        guard loaded else { throw StoreError.notLoaded }
        // NSFileCoordinator serializes writers in cooperating processes. The
        // revision check is inside the coordination to avoid lost updates.
        var coordinationError: NSError?
        var operationError: Error?
        let coordinator = NSFileCoordinator()
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        coordinator.coordinate(writingItemAt: url, options: .forReplacing, error: &coordinationError) { _ in
            do {
                guard try self.read()?.revision == self.revision else { throw StoreError.conflict }
                let next = UUID()
                let envelope = Envelope(version: 1, scope: self.scope, revision: next, draft: draft)
                try JSONEncoder().encode(envelope).write(to: self.url, options: .atomic)
                self.revision = next
            } catch { operationError = error }
        }
        if let coordinationError { throw coordinationError }
        if let operationError { throw operationError }
    }
}
