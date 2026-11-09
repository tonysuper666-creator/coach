import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var connection: ConnectionModel
    @State private var address = ""
    @State private var code = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    switch connection.state {
                    case .loading:
                        ProgressView("正在恢复连接…")
                    case .unpaired:
                        pairing
                    case .connected:
                        LearningView(connection: connection)
                    case .unavailable, .revoked:
                        Text("无法连接").font(.title2.bold())
                        if connection.state == .unavailable {
                            Button("重试") { Task { await connection.reconnect() } }
                                .disabled(connection.busy)
                        }
                        Button("重新配对") { connection.forget() }
                    }
                    if let message = connection.message {
                        Text(message).foregroundStyle(Appearance.accent).accessibilityIdentifier("connectionMessage")
                    }
                }
                .frame(maxWidth: 600, alignment: .leading)
                .padding(24)
            }
            .background(Appearance.paper)
            .foregroundStyle(Appearance.ink)
            .font(Appearance.font)
            .tint(Appearance.accent)
            .navigationTitle(Appearance.name)
        }
        .task { await connection.restore() }
    }

    private var pairing: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("连接你的 Mac").font(.title2.bold())
            Text("在 Mac 上获取配对码，并填写 Tailscale 地址与端口。")
            TextField("http://地址:端口", text: $address)
                .textContentType(.URL).keyboardType(.URL)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .accessibilityIdentifier("serverAddress")
            TextField("六位配对码", text: $code)
                .keyboardType(.numberPad).accessibilityIdentifier("pairingCode")
            Button(connection.busy ? "正在连接…" : "配对") {
                Task { await connection.pair(address: address, code: code) }
            }
            .buttonStyle(.borderedProminent)
            .disabled(connection.busy || address.isEmpty || code.isEmpty)
            .accessibilityIdentifier("pairButton")
        }
        .textFieldStyle(.roundedBorder)
    }
}
