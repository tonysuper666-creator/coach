import Foundation

/// A synthetic, scoped draft written and read by separate executable runs.
@main struct AnchorProcessCheck {
    static func main() throws {
        let args = CommandLine.arguments
        guard args.count == 3 else { fatalError("expected write|read and draft URL") }
        let store = FileReaderDraftStore(url: URL(fileURLWithPath: args[2]), scope: "synthetic-process")
        let a = ReaderNoteAnchor(document: .init(document: "document-a", version: "1"), location: .pdf(pageIndex: 1, exactText: "same source"))
        let b = ReaderNoteAnchor(document: .init(document: "document-b", version: "1"), location: a.location)
        let otherPage = ReaderNoteAnchor(document: a.document, location: .pdf(pageIndex: 2, exactText: "same source"))
        let otherVersion = ReaderNoteAnchor(document: .init(document: "document-a", version: "2"), location: a.location)
        if args[1] == "write" {
            guard try store.load() == nil else { fatalError("fixture already exists") }
            var draft = ReaderDraft(position: .pdf(pageIndex: 4, columnIndex: 0), notes: ["source": "legacy"])
            draft.setNote("note-a", at: a); draft.setNote("note-b", at: b)
            try store.save(draft)
            print("write: two documents, same source; moved to page 5")
        } else if args[1] == "read" {
            guard let draft = try store.load(), draft.note(at: a) == "note-a", draft.note(at: b) == "note-b",
                  draft.note(at: otherPage) == nil, draft.note(at: otherVersion) == nil,
                  draft.notes["source"] == "legacy", draft.position == .pdf(pageIndex: 4, columnIndex: 0) else {
                fatalError("anchor isolation or persistence failed")
            }
            print("read: independent document notes restored; other page/version empty; legacy retained")
        } else { fatalError("unknown operation") }
    }
}
