import SwiftUI

@main
struct ReaderLayoutHostApp: App {
    @State private var fixtureNarrow = false
    @State private var fixtureLarge = false

    var body: some Scene {
        WindowGroup {
            if ProcessInfo.processInfo.arguments.contains("-adaptiveFixture") {
                VStack {
                    HStack {
                        Button("切换测试宽度") { fixtureNarrow.toggle() }
                        Button("切换测试字号") { fixtureLarge.toggle() }
                    }
                    HostView()
                        .dynamicTypeSize(fixtureLarge ? .accessibility3 : .large)
                        .frame(maxWidth: fixtureNarrow ? 280 : .infinity)
                }
            } else if ProcessInfo.processInfo.arguments.contains("-largeTypeFixture") {
                fixture.dynamicTypeSize(.accessibility3)
            } else {
                fixture
            }
        }
    }

    private var fixture: some View {
        // UI-test-only container width, not a device fact.
        HostView().frame(maxWidth: ProcessInfo.processInfo.arguments.contains("-narrowFixture") ? 280 : .infinity)
    }
}

private struct HostView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var content: FlatReaderLayout.Content = .pdf
    @State private var closed = false
    @State private var systemHinge: ReaderGeometry.Hinge = .unavailable
    @State private var manual: ReaderPose?
    @State private var page = 1
    @State private var selection = false
    @State private var draft = ""
    @State private var drawer = false
    @State private var showingSource = false

    var body: some View {
        if #available(iOS 27.1, *) {
            host.onHingeChange { _, context in
                guard let status = context.hinge?.status else {
                    systemHinge = .unavailable
                    return
                }
                if status == .closed {
                    systemHinge = .closed
                } else if status == .fullyOpen {
                    systemHinge = .open
                } else if status == .partiallyOpen {
                    systemHinge = .partiallyOpen
                } else {
                    systemHinge = .unavailable
                }
            }
        } else {
            host
        }
    }

    private var host: some View {
        GeometryReader { proxy in
            let division = systemDivision(proxy)
            let geometry = ReaderGeometry(width: proxy.size.width, height: proxy.size.height,
                                          hinge: closed ? .closed : systemHinge,
                                          division: division, surface: .unknown,
                                          compactWidth: proxy.size.width < 600)
            let pose = ReaderPoseResolver.resolve(geometry, manual: manual)
            let layout = FlatReaderLayout.resolve(content: content, width: proxy.size.width,
                                                  height: proxy.size.height)
            Group {
                if #available(iOS 27.1, *), division != .none {
                    ArrangementView {
                        reader(proxy: proxy, pose: pose, layout: layout)
                    } secondary: {
                        if drawer {
                            drawerContent(width: proxy.size.width)
                        } else {
                            Text("第二栏示例").frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).padding()
                        }
                    }
                } else {
                    VStack(alignment: .leading, spacing: 0) {
                        reader(proxy: proxy, pose: pose, layout: layout)
                        if drawer {
                            drawerContent(width: proxy.size.width)
                                .frame(height: proxy.size.height * (dynamicTypeSize.isAccessibilitySize ? 0.6 : 0.4))
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    private func systemDivision(_ proxy: GeometryProxy) -> ReaderGeometry.Division {
        if #available(iOS 27.1, *),
           let region = proxy.reservedRegions(kind: .division).first(where: { $0.isActive && !$0.frame.isEmpty }) {
            return region.frame.height >= region.frame.width ? .vertical : .horizontal
        }
        return .none
    }

    private func reader(proxy: GeometryProxy, pose: ReaderPose, layout: FlatReaderLayout) -> some View {
              ScrollView {
               VStack(alignment: .leading, spacing: 12) {
                Text("阅读布局示例").font(.title2.bold())
                Text("组件宿主 · 自造内容").font(.caption).foregroundStyle(.secondary)
                let controls = proxy.size.width < 340 || dynamicTypeSize.isAccessibilitySize
                    ? AnyLayout(VStackLayout(alignment: .leading)) : AnyLayout(HStackLayout())
                controls {
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
                Text(manual != nil ? "手选形态（模拟输入）" : (closed ? "模拟合上" : "系统输入"))
                    .font(.caption).foregroundStyle(.secondary)
                if ProcessInfo.processInfo.arguments.contains("-systemDiagnostics") {
                    Text(systemDiagnostics(proxy)).font(.caption2).accessibilityIdentifier("systemGeometry")
                }
                ReaderCanvas(pose: pose, layout: layout, page: $page, selection: $selection,
                             draft: $draft, drawer: $drawer)
                    // Exercise an actual child view teardown on pose changes.
                    .id(poseLabel(pose))
               }.padding()
                .frame(maxWidth: .infinity, alignment: .topLeading)
              }.frame(maxHeight: .infinity).clipped().accessibilityIdentifier("readerScroll")
    }

    private func systemDiagnostics(_ proxy: GeometryProxy) -> String {
        if #available(iOS 27.1, *) {
            let divisions = proxy.reservedRegions(kind: .division).map { "\($0.frame) active=\($0.isActive) margins=\($0.margins)" }
            let occlusions = proxy.reservedRegions(kind: .occlusion).map { "\($0.frame) active=\($0.isActive) margins=\($0.margins)" }
            return "window=\(proxy.size) hinge=\(systemHinge) division=\(divisions) occlusion=\(occlusions)"
        }
        return "window=\(proxy.size) hinge=\(systemHinge) reserved=unavailable"
    }

    private func drawerContent(width: CGFloat) -> some View {
                    VStack(alignment: .leading, spacing: 0) {
                        let headerLayout = width < 340 || dynamicTypeSize.isAccessibilitySize
                            ? AnyLayout(VStackLayout(alignment: .leading)) : AnyLayout(HStackLayout(alignment: .top))
                        headerLayout {
                            Text("内容抽屉示例").font(.headline)
                                .fixedSize(horizontal: false, vertical: true)
                                .layoutPriority(1)
                                .accessibilityIdentifier("sampleDrawerTitle")
                            Button("收起") { drawer = false }
                        }.padding([.horizontal, .top])
                        ScrollView {
                          VStack(alignment: .leading, spacing: 8) {
                        if showingSource {
                            Text("出处示例：这是一句自造的示例文字。")
                            Button("返回题目") { showingSource = false }
                        } else {
                            Text("题目示例：这段话讲了什么？")
                            Button("查看出处") { showingSource = true }
                        }
                          }.padding().frame(maxWidth: .infinity, alignment: .topLeading)
                        }.accessibilityIdentifier("sampleDrawer")
                    }
                    .clipped()
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
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
                    .submitLabel(.done)
                HStack {
                    Button("下一页") { page += 1 }
                    Button("打开抽屉") { drawer.toggle() }
                }.buttonStyle(.bordered)
            }.frame(maxWidth: .infinity, alignment: .leading)
    }

    private var sampleText: some View {
        Text("这是用于检查布局的示例内容。阅读时可以保留当前页和笔记。")
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("sampleBody")
    }
}
