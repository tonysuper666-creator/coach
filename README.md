# Coach（代号）

把 StudyCoach 的学习闭环与证据规则、DeepTutor 的导师能力合在一起，从 0 重写的学习应用。目标：安卓原生 + macOS 桌面（之后 Windows），服务端先放用户的 Mac，AI 经订阅 CLI。

- 已定决定与背景：`docs/background/新应用立项方案v2.md`
- 设计委员会（Opus 5.5 / Sonnet 5.5 / GPT-6-Astra 各出完整设计 → 交叉评审 → 分歧核查）：`docs/design/`
- 第一件事：整合三份设计、裁决分歧，产出 `docs/DESIGN.md` 与 ADR，再按 M0 开卡。见 Multica 里负责人 Mika 的立项卡。
- 参考源码（只读，不在本仓库里）：StudyCoach（`gh repo clone tonysuper666-creator/studycoach`，已归档）、DeepTutor（`~/DeepTutor`）。

## 开发

工具链按 ADR-0001 固定：JDK 17、Gradle 9.5.0、Kotlin 2.4.0、CMP 1.12.1、AGP 9.3.2、compileSdk 37 / minSdk 34 / targetSdk 35。依赖版本见 `gradle/libs.versions.toml`。共享模块使用 AGP 9 的 Android KMP 插件，安卓应用为独立模块（[官方迁移说明](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html)）。

先把 `local.properties.example` 复制到 `~/Coach/config/local.properties`，填写本机 tailnet IPv4、教材目录，以及验收脚本所用的 SDK / Tailscale CLI 路径。APK 的 BuildConfig 与网络安全 XML 从该配置生成到 build 目录；生成文件不入库。设置 `ANDROID_HOME` 或在未入库的 `local.properties` 写 `sdk.dir=...`。SDK 实际包名为 `platforms;android-37.0`：

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0'
tools/dev/heavy ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :desktopApp:createDistributable :server:app:installDist :server:store:installDist --max-workers=2
tools/dev/heavy ./gradlew :desktopApp:run --offline -Pcoach.autoExitSeconds=5 --max-workers=2
```

首次联网同步包括 Compose 安卓构件与桌面打包的 JDK probe。以后桌面运行可 `--offline`。产物为 `androidApp/build/outputs/apk/debug/androidApp-debug.apk` 和 `desktopApp/build/compose/binaries/main/app/Coach.app`。

**每个重命令都用 `tools/dev/heavy COMMAND ...`**（包括 Gradle、SDK 安装、模拟器）。它用 `~/dev/.coach-heavy.lock` 的 `mkdir` 原子锁串行化，等待时打印持有者 PID 与命令；子进程以 `nice -n 15` 运行，退出或中断时释放锁。Gradle 默认 `org.gradle.workers.max=2`。不要绕过锁；只有确认持有者已不存在，才人工清理异常退出遗留的锁。测试可用 `COACH_HEAVY_LOCK` 指定隔离锁目录。模拟器运行期间先前台完成 APK 编译，再用 adb 安装/验收，不同时开另一重构建。

2026-11-02 00:00–15:00 不跑模拟器、Gradle 全量构建或 live AI。预计超过 10 分钟的命令放进 `screen -dmS`，启动它的前台调用仍须收集退出码与日志后才能交付。

### 服务端与网络小样

M1 常驻安装、停止/卸载、真实恢复复验见 [macOS 常驻服务](tools/dev/service.md)。LaunchAgent 安装后由 Ktor 托管桥副本与 mathsvc；`run-all.sh` 继续供 M0 手动隔离使用。

```sh
tools/dev/heavy ./gradlew :server:app:installDist --max-workers=2
python3 tools/dev/check-server.py
```

两路独立监听，永不绑定 `0.0.0.0`。tailnet 监听有监督协程，1 秒到 60 秒退避；接口消失后重绑。账本接入后 `GET /v1/hello` 返回带 epoch 与备份水位的 JSON，WS 使用 `/v1/ws`，包括本机回环在内都要求设备令牌。验证脚本使用隔离数据目录；首次启动本机令牌位于该目录的 `device-token`（权限 0600）。令牌不进入 URL。完整接口、回档复验与三进程起停见 [账本复验](tools/dev/ledger.md)。

Android 的明文例外只允许 `coach.tailHost` 指定的本机 tailnet IP，其余地址禁用。在服务端运行且模拟器已启动时，用 adb 安装上面两个 APK，再执行：

```sh
adb -s emulator-5554 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s emulator-5554 install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
adb -s emulator-5554 shell am start -n com.coach.android/.MainActivity
# 网络测试需要隔离服务令牌；check-android.py 会注入且不打印令牌
python3 tools/dev/check-android.py
```

仪器测试实际请求 HTTP 和 WS，断言允许地址收到认证后的 epoch，未允许的 `10.0.2.2` 在发包前以 `CLEARTEXT` 拒绝。两端显示可用窗口 dp、密度、字体缩放和 FoldingFeature；桌面恒 FLAT，安卓订阅真实 WindowInfoTracker。

### SQLite 与真实教材

```sh
pages=$(python3 tools/dev/stage-textbook-pages.py)
tools/dev/heavy ./gradlew :server:store:run --args="$pages" --max-workers=2
```

按 `coach.textbookDir` 只读取教材 Markdown，逐字拷贝三个完整 PDF 页到 `~/Coach/corpus/s0/`，不连接 DeepTutor 原数据库，不读 sealed、环境配置或凭证，教材不入 Git。冒烟在内存库验证 SQLite 版本、FTS5 编译选项、porter unicode61 / trigram、MATCH/rank/bm25，再验证 SQLDelight 生成的建表、MATCH/rank/bm25 查询与原生执行接口。

省略 `--args` 时使用资源中的三段明确标注的合成中英用例，三个查询均必须命中；这只证明 SQLite/SQLDelight 的检索能力。显式传入本机真实页目录时，必须分别报告真实结果，不能把合成用例混入教材来制造命中。当前三个英文原文页里“逆向选择”字面命中为 0；adverse selection 和 Grossman 实际命中。

SQLDelight 固定 2.4.0、方言 sqlite-3-38。上游 [#5986](https://github.com/sqldelight/sqldelight/pull/5986) 已合入该版本（GitHub compare 显示 2.4.0 包含 merge commit），本工程也实际编译并执行了 ORDER BY rank。安卓仪器测试另执行 `select sqlite_version()`，不以 SDK 版本推断系统 SQLite。

桌面 JDK 厂商检查按 ADR-0001 的 Homebrew JDK 17 决定关闭，实际打包、启动和 Metal 渲染需实测；不跳过测试。macOS 的应用包版本首位必须为正，当前包版本为 1.0.0。无系统录屏权限时，可以捕获本应用已经显示的 Skia 渲染表面（仅开发验收使用）：

```sh
JAVA_TOOL_OPTIONS="-Dcoach.autoExitSeconds=3 -Dcoach.capturePath=$PWD/coach-window.png" desktopApp/build/compose/binaries/main/app/Coach.app/Contents/MacOS/Coach
```

S0 的 Ktor 引擎使用 Netty；CIO 3.3.3 在绑定失败的实测中卡住启动，不能满足重试要求。Netty 分别限制连接/工作/调用线程组为 1/1/2，并使用单一进程退出钩子按顺序停止两路引擎。

复验脚本（日志写入 `.s0-evidence/`）：

```sh
python3 tools/dev/check-heavy.py
python3 tools/dev/check-server.py
python3 tools/dev/check-desktop.py
COACH_VERIFY_PEER=my-tailnet-peer python3 tools/dev/check-android.py
```

`check-android.py` 在端口 5568 启动 `sable-api35`，装 APK、实际测 HTTP/WS、截屏并退出模拟器；已占用时拒绝启动。可省略 `COACH_VERIFY_PEER`，此时不测 SSH 对端或 serve 转发；设为已有 tailnet 对端 SSH 别名时才测跨设备访问。仅当 serve 没有任何现有配置时临时加端口 18793，测完只撤这一条。`COACH_SKIP_SERVE=1` 可避免重复已留证的转发实测。测试不运行 AI。
