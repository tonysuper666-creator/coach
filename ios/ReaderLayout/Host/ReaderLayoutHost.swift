import SwiftUI

@main
struct ReaderLayoutHostApp: App {
    var body: some Scene { WindowGroup { HostView() } }
}

private struct HostView: View {
    @State private var content: FlatReaderLayout.Content = .pdf
    @State private var closed = false
    @State private var manual = false
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
            let pose = ReaderPoseResolver.resolve(geometry, manual: manual ? .compact : nil)
            let layout = FlatReaderLayout.resolve(content: content, width: proxy.size.width,
                                                  height: proxy.size.height)
            VStack(alignment: .leading, spacing: 12) {
                Text("阅读布局示例").font(.title2.bold())
                Text("组件宿主 · 自造内容").font(.caption).foregroundStyle(.secondary)
                HStack {
                    Button(content == .pdf ? "PDF" : "EPUB") { content = content == .pdf ? .epub : .pdf }
                    Button(closed ? "合上" : "直板") { closed.toggle() }
                    Button(manual ? "手动" : "自动") { manual.toggle() }
                }.buttonStyle(.bordered)
                Text("\(pose == .compact ? "直板布局" : "其他布局") · \(layoutLabel(layout))")
                    .font(.subheadline)
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        Text("第 \(page) 页").font(.headline)
                        HStack(alignment: .top, spacing: 20) {
                            Text("这是用于检查布局的示例内容。阅读时可以保留当前页和笔记。")
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if layout == .epubFacingPages {
                                Text("第二页示例内容").frame(maxWidth: .infinity, alignment: .leading)
                                    .accessibilityIdentifier("secondPage")
                            }
                        }
                        Toggle("标记这一段", isOn: $selection)
                        TextField("写下笔记", text: $draft).textFieldStyle(.roundedBorder)
                        HStack {
                            Button("下一页") { page += 1 }
                            Button("打开抽屉") { drawer.toggle() }
                        }.buttonStyle(.bordered)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
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
