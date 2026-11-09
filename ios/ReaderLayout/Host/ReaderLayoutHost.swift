import SwiftUI

@main
struct ReaderLayoutHostApp: App {
    var body: some Scene { WindowGroup { HostView() } }
}

private struct HostView: View {
    @State private var content: FlatReaderLayout.Content = .pdf
    @State private var closed = false
    @State private var manual: ReaderPose?
    @State private var page = 1
    @State private var selection = false
    @State private var draft = ""
    @State private var drawer = false

    var body: some View {
        GeometryReader { proxy in
            let geometry = ReaderGeometry(width: proxy.size.width, height: proxy.size.height,
                                          hinge: closed ? .closed : .unavailable,
                                          division: .none, surface: .unknown,
                                          compactWidth: proxy.size.width < 600)
            let pose = ReaderPoseResolver.resolve(geometry, manual: manual)
            let layout = FlatReaderLayout.resolve(content: content, width: proxy.size.width,
                                                  height: proxy.size.height)
            VStack(alignment: .leading, spacing: 12) {
                Text("阅读布局示例").font(.title2.bold())
                Text("组件宿主 · 自造内容").font(.caption).foregroundStyle(.secondary)
                HStack {
                    Button(content == .pdf ? "PDF" : "EPUB") { content = content == .pdf ? .epub : .pdf }
                    Button(closed ? "合上" : "直板") { closed.toggle() }
                    Menu("选择形态") {
                        Button("自动选择") { manual = nil }
                        ForEach(poseChoices, id: \.name) { choice in
                            Button(choice.name) { manual = choice.pose }
                                .accessibilityIdentifier("pose-\(choice.name)")
                        }
                    }.accessibilityIdentifier("poseMenu")
                }.buttonStyle(.bordered)
                Text("\(poseLabel(pose)) · \(layoutLabel(layout))")
                    .font(.subheadline)
                Text("形态为模拟示例，未接入折痕避让").font(.caption).foregroundStyle(.secondary)
                ReaderCanvas(pose: pose, layout: layout, page: $page, selection: $selection,
                             draft: $draft, drawer: $drawer)
                    // Exercise an actual child view teardown on pose changes.
                    .id(poseLabel(pose))
                if drawer {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack { Text("内容抽屉").font(.headline); Spacer(); Button("收起") { drawer = false } }
                        Text("这里是抽屉容器示例。")
                    }
                    .padding().frame(maxWidth: .infinity, minHeight: proxy.size.height * 0.4, alignment: .topLeading)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
                    .frame(maxHeight: proxy.size.height * 0.45)
                }
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    private func layoutLabel(_ layout: FlatReaderLayout) -> String {
        switch layout {
        case .pdfColumnFocus: "PDF 列聚焦"
        case .epubSinglePage: "EPUB 单页"
        case .epubFacingPages: "EPUB 双页"
        }
    }
}

private let poseChoices: [(name: String, pose: ReaderPose)] = [
    ("合上", .compact), ("展开竖拿", .openPortrait), ("展开横拿", .openLandscape),
    ("半折书本", .book), ("半折笔记本", .laptop), ("平放桌面", .tabletop),
]

private func poseLabel(_ pose: ReaderPose) -> String {
    switch pose {
    case .compact: "直板布局"
    case .openPortrait: "展开竖拿"
    case .openLandscape: "展开横拿"
    case .book: "半折书本"
    case .laptop: "半折笔记本"
    case .tabletop: "平放桌面"
    }
}

private struct ReaderCanvas: View {
    let pose: ReaderPose
    let layout: FlatReaderLayout
    @Binding var page: Int
    @Binding var selection: Bool
    @Binding var draft: String
    @Binding var drawer: Bool
    @State private var instance = UUID().uuidString

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("布局示例").font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("canvasInstance-\(instance)")
                Text("第 \(page) 页").font(.headline)
                if pose == .book || pose == .openLandscape || layout == .epubFacingPages {
                    HStack(alignment: .top, spacing: 20) {
                        sampleText
                        Text("第二栏示例").frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("secondPage")
                    }
                } else {
                    sampleText
                    if pose == .laptop || pose == .openPortrait { Text("下方区域示例") }
                }
                Toggle("标记这一段", isOn: $selection)
                TextField("写下笔记", text: $draft).textFieldStyle(.roundedBorder)
                HStack {
                    Button("下一页") { page += 1 }
                    Button("打开抽屉") { drawer.toggle() }
                }.buttonStyle(.bordered)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var sampleText: some View {
        Text("这是用于检查布局的示例内容。阅读时可以保留当前页和笔记。")
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
