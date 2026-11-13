import SwiftUI

public struct ReaderQuestion: Identifiable {
    public let id: String
    public let text: String
    public init(id: String, text: String) { self.id = id; self.text = text }
}

/// A host-supplied reading surface and resolver keep this view independent of
/// the unmerged client and the platform-specific PDF/EPUB implementation.
public struct FlatReaderView<Reading: View>: View {
    @State private var session: ReaderSession
    @State private var lookupID: UUID?
    private let questions: [ReaderQuestion]
    private let provider: any ReaderSourceProvider
    private let reading: Reading

    public init(draft: ReaderDraft, questions: [ReaderQuestion],
                provider: any ReaderSourceProvider, @ViewBuilder reading: () -> Reading) {
        _session = State(initialValue: ReaderSession(draft: draft))
        self.questions = questions
        self.provider = provider
        self.reading = reading()
    }

    public var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                ZStack(alignment: .topTrailing) {
                    reading.frame(maxWidth: .infinity, maxHeight: .infinity)
                    Button("题目 \(questions.count)") { lookupID = nil; session.openQuestions() }
                        .buttonStyle(.bordered).padding(8)
                }
                if session.isQuestionListOpen || session.source != nil {
                    drawer.frame(height: geometry.size.height * 0.5)
                }
            }
        }
    }

    private var drawer: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let source = session.source {
                    Button("返回题目") { session.returnToQuestion() }
                    if case let .verified(_, passage) = source {
                        Text(passage).padding(8).background(Color.yellow.opacity(0.3))
                    } else if let message = source.message { Text(message) }
                } else {
                    ForEach(questions) { question in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(question.text)
                            Button("查看出处") {
                                session.selectQuestion(question.id)
                                let request = UUID()
                                lookupID = request
                                Task {
                                    let result = await provider.resolveSource(questionID: question.id)
                                    guard lookupID == request else { return }
                                    session.showSource(result)
                                }
                            }
                        }
                    }
                }
            }.frame(maxWidth: .infinity, alignment: .leading).padding()
        }
        .background(.regularMaterial)
        .gesture(DragGesture(minimumDistance: 40).onEnded { value in
            if value.translation.height > 40, session.source != nil { session.returnToQuestion() }
        })
    }
}

#if DEBUG
private struct SyntheticSourceProvider: ReaderSourceProvider {
    func resolveSource(questionID: String) async -> SourceResolution {
        .verified(position: .pdf(pageIndex: 0, columnIndex: 0), passage: "这是用于测试的句子。")
    }
}

#Preview {
    FlatReaderView(
        draft: ReaderDraft(position: .pdf(pageIndex: 0, columnIndex: 0)),
        questions: [ReaderQuestion(id: "synthetic", text: "这是一道测试题。")],
        provider: SyntheticSourceProvider()
    ) {
        Text("这是用于测试的阅读页面。")
    }
}
#endif
