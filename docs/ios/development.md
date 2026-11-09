# iOS 开发状态

`ios/CoachApp.xcodeproj` 是尚未完成的模拟器应用，提供配对入口和 Keychain 凭据恢复后的连接检查。已接入主线练习的课程入口、题目、提交和回执；阅读批注及 WebSocket 尚未接入。不要将连接界面当作完整客户端验收。

模拟器构建：`tools/dev/heavy xcodebuild -project ios/CoachApp.xcodeproj -scheme CoachApp -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- build`。未签名的模拟器构建在本机无法读取 Keychain，不可用于验证凭据恢复。

ATS 例外在构建时以 `COACH_ATS_HOST` 设置单个主机，默认是仅用于占位的 `127.0.0.1`，实际目标须用本机私有构建配置注入；例外不包含子域，不设置全局允许。输入的服务地址仍由连接库限制为 Tailscale 地址。隔离模拟器已用单个 Tailscale 数值地址验证 HTTP；WebSocket 尚未验证。不要将私有地址、配对码或令牌提交到仓库。

下一步验证真实 WebSocket 鉴权与断线恢复，并以模拟器实际操作覆盖配对至答题回执的完整界面流程。阅读和批注需要对应接口进入 main 后联测；缺失接口不应模拟成功。模拟器 bundle ID 是开发占位，正式标识和签名由后续打包任务办理。

### Isolated real-server simulator check

From the repository root, build the existing server distribution and run the simulator
check under the shared heavy-work lock:

```sh
tools/dev/heavy ./gradlew :server:app:installDist --max-workers=2 --console=plain
tools/dev/heavy --emulator python3 ios/Tools/run-isolated-connection-tests.py
```

The runner requires a local Tailscale IPv4 interface, Xcode, and the iOS 27.0
simulator runtime with an iPhone 17 Pro device type. It creates a disposable simulator,
server data directory, random ports, and per-run ATS configuration. The temporary
server binds loopback and the local tailnet address; it does not use the normal Coach
port or data. Do not run it against production. Its private fixture and generated
credentials stay in temporary files and are removed with the simulator at the end.

Two separate `xcodebuild test-without-building` invocations exercise the app's
connection model: pair and store the credential, then reload it from Keychain in a
new test-host process, reconnect, revoke it through the isolated server, and verify
the model reports rejection. This does not test manual UI interaction, WebSockets,
exercise workflows, device rotation, or physical devices.


### Isolated mainline exercise check

Build the server and run the separate learning check using the latest main version
of `tools/dev/heavy` and the same simulator lock:

```sh
tools/dev/heavy --local ./gradlew :server:app:installDist --max-workers=2 --console=plain
tools/dev/heavy --emulator python3 ios/Tools/run-learning-tests.py
```

This uses the synthetic `Synthetic-W01.json` fixture and an isolated Ktor server.
It pairs, loads the mainline week and submits a shuffled answer, then starts a new
test-host process and retries the same submission from Keychain. To model a lost
reply, the test restores the committed submission as pending; it does not inject a
real network failure. The server must return the same receipt and mastered state.
An optional `COACH_IOS_SCREENSHOT` path captures the app's learning entry after
the test; it does not capture or verify the entire UI flow. These checks do not
cover WebSocket, manual UI navigation, rotation, or Dynamic Type.

The app retains an unconfirmed submission in device-only Keychain before sending
it and retries its original ID. Clearing it is an explicit user action; an answer
may already have reached the server, so the user should reload to check its state.
