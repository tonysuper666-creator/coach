# iOS 开发状态

`ios/CoachApp.xcodeproj` 是尚未完成的模拟器应用，提供配对入口和 Keychain 凭据恢复后的连接检查。学习内容、阅读批注、做题及 WebSocket 尚未接入。不要将连接界面当作完整客户端验收。

模拟器构建：`tools/dev/heavy xcodebuild -project ios/CoachApp.xcodeproj -scheme CoachApp -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- build`。未签名的模拟器构建在本机无法读取 Keychain，不可用于验证凭据恢复。

ATS 例外在构建时以 `COACH_ATS_HOST` 设置单个主机，默认是仅用于占位的 `127.0.0.1`，实际目标须用本机私有构建配置注入；例外不包含子域，不设置全局允许。输入的服务地址仍由连接库限制为 Tailscale 地址。此配置对数值地址的实际 HTTP/WS 行为尚未验证，不能据此声称已能访问目标。不要将私有地址、配对码或令牌提交到仓库。

下一步用隔离服务和合成资料在模拟器中实际验证受限 ATS、真实配对及鉴权、Keychain 重启恢复和吊销拒绝，再接入现有做题协议。阅读和批注需要对应接口进入 main 后联测；缺失接口不应模拟成功。模拟器 bundle ID 是开发占位，正式标识和签名由后续打包任务办理。
