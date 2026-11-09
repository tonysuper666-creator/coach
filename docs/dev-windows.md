# 在 Windows（winbox）开发 Coach

本页记录 TON-1415 在 2026-11-09 的实际操作。Windows 负责源码修改、Android debug APK 编译和共享模块 JVM 测试；设备验收和服务端运行仍按下文交给 Mac。

## 检出与分支

在 Multica 分配的 PowerShell 工作目录运行。CLI 路径使用 daemon 注入的变量：

```powershell
& "$env:MULTICA_CLI_PATH" repo checkout https://github.com/tonysuper666-creator/coach --ref main
Set-Location .\coach
git fetch origin main
git switch -c agent/builder-w/ton-1415-windows-smoke origin/main
git log -1 --format='%H %s'
```

本次落点为 `main`，起点提交为 `cf1f159833a4fd5fadad0b8450b147b4e413f7b2`。后续任务使用各自的分支名，并先确认检出目录没有需要保留的修改。

## 本机环境

本机已有 Temurin JDK 21、JDK 17，以及 Android SDK `platforms;android-37.0` / build-tools `37.0.0`。下面的设置只影响当前 PowerShell 进程；Gradle 使用共用缓存，模块的 `jvmToolchain(17)` 保持仓库定义。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:GRADLE_USER_HOME = 'D:\agent\gradle-home'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& "$env:JAVA_HOME\bin\java.exe" --version
(Get-Counter '\Memory\Available MBytes').CounterSamples[0].CookedValue
Test-Path "$env:USERPROFILE/Coach/config/local.properties"
```

本次 Java 输出为 Temurin `21.0.12.1+1-LTS`，构建前可用内存为 7192 MB。大构建前必须检查内存；低于 4096 MB 时先等待，再重新检查。

每个新 worktree 的根目录建立 `local.properties`，内容如下。该文件被 Git 忽略，不提交：

```properties
sdk.dir=D\:\\agent\\android-sdk
```

另一个配置文件是用户目录下的 `Coach/config/local.properties`。本机已配置 `coach.tailHost`，Android 构建会从那里生成 BuildConfig 和网络安全资源；本页只检查文件存在，不展示其中的地址。这两个同名文件用途不同。

使用仓库原有的 `gradle.properties`：JVM 堆上限 2 GB、两个 worker、单次 daemon、Kotlin 在进程内编译。不要调大这些参数。

## 编译与共享模块测试

在仓库根目录、同一个 PowerShell 环境运行；日志保存在仓库外的任务工作目录，耗时覆盖 Gradle 启动、编译和测试：

```powershell
$buildTimer = [System.Diagnostics.Stopwatch]::StartNew()
.\gradlew.bat :androidApp:assembleDebug :shared:domain:jvmTest :shared:protocol:jvmTest :shared:client:jvmTest 2>&1 | Tee-Object -FilePath ../windows-build.log
$buildExit = $LASTEXITCODE
$buildTimer.Stop()
$buildSummary = 'BUILD_EXIT_CODE={0}; ELAPSED_SECONDS={1:N3}' -f $buildExit, $buildTimer.Elapsed.TotalSeconds
$buildSummary | Tee-Object -FilePath ../windows-build-summary.txt
```

本次输出为 `BUILD SUCCESSFUL in 1m 11s`，94 个任务执行，退出码 `0`；计时为 `72.209` 秒。原始日志作为 TON-1415 交付评论附件保存。

| JVM 任务 | 实际结果 |
| --- | --- |
| `:shared:domain:jvmTest` | 63 个测试，失败 / 错误 / 跳过均为 0。 |
| `:shared:protocol:jvmTest` | `NO-SOURCE`，当前没有测试用例。 |
| `:shared:client:jvmTest` | 74 个测试，失败 / 错误 / 跳过均为 0。 |

Gradle 提示共享模块尚未启用 Android host tests；这次执行的是 JVM 测试，Android 设备测试另行验收。

## APK 签名

同一 PowerShell 中运行，使用上面设置的 JDK：

```powershell
& 'D:\agent\android-sdk\build-tools\37.0.0\apksigner.bat' verify --print-certs androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

产物为 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。本次核验退出码为 `0`，证书 SHA-256 为 `4435a6c41f1c4cbae89765961064df3c488f82a8dd5ae296dc9475a31a6d4e30`，符合与 Mac 共用 debug 证书的 `4435a6c4` 前缀要求。共享 debug keystore 由现有环境提供，不把密钥复制进仓库。

## 提交文档与草稿 PR

本次只提交本页。GitHub CLI 使用机器上的固定路径和已有登录：

```powershell
& 'C:\Program Files\GitHub CLI\gh.exe' auth status
git add docs/dev-windows.md
git commit -m 'docs: document verified Windows development workflow'
git push -u origin HEAD
& 'C:\Program Files\GitHub CLI\gh.exe' pr create --draft --base main --head agent/builder-w/ton-1415-windows-smoke --title 'TON-1415: document verified Windows development workflow' --body-file ../pr-body.md
```

`pr-body.md` 是任务工作目录内的 UTF-8 文件，包含本次验证摘要及 `Closes TON-1415`。后续任务替换分支、标题和关联卡号；登录或推送失败时交给 Root 处理。

## 留在 Mac 的工作与边界

以下是本任务的运行环境约定，本次 Windows 冒烟没有执行这些验收：

| 工作 | 留在 Mac 的原因 |
| --- | --- |
| `server:*` 测试 | 现有测试按 macOS 环境编写，需要 Mac 独立验证。 |
| Android 模拟器 | winbox 没有开启虚拟化，本任务不变更系统功能。 |
| OPPO 真机安装、启动与交互验收 | 设备的 adb 连接在 Mac，由 Mac 侧接管验收。 |
| 生产服务及 `tools/dev/heavy` 锁 | 属于 Mac 上的运行与资源协调环境；Windows 使用本页的内存检查和 Gradle 参数。 |
| BiliPai Windows 验收 | 使用 Mac 上的 Parallels `BiliPai-Win11`（`ssh winvm`），沿用该项目的专用技能。 |

winbox 上不重启、不关机，不改系统设置、Windows 功能、驱动、防火墙或 Defender，不操作交易软件、Edge 或用户账户，不在用户桌面会话做界面自动化，也不开 Android 模拟器。需要清理文件时先移入 `D:\agent\_trash`。本次只验证构建、共享模块测试和签名；应用启动、设备交互、服务端测试与生产部署均未验证。
