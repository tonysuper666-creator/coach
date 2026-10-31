# macOS 常驻服务

目标：登录后 30 秒内，两路认证监听、桥副本 8791、公式服务都可用；子进程死亡或健康检查失败后 60 秒内恢复。Ktor 是唯一重启决策者，LaunchAgent `local.coach.server` 只重启 Ktor。不防系统休眠。Mac 关机或睡着时手机走离线，不能保证在线。

## 安装

先配置 `~/Coach/config/local.properties` 中的 `coach.tailHost` 为本机 Tailscale IPv4。需要 JDK 17、Python 3、Node 22、已登录的订阅 CLI；不需要管理员权限。不修改 DeepTutor、8790、Tailscale 配置或账户。

```sh
tools/dev/heavy ./gradlew :server:app:installDist --max-workers=2
python3 tools/dev/coach-service.py install
python3 "$HOME/Coach/service/control.py" status
```

2026-10-31 08:00 GMT 前按今晚调度例外：构建前 `pgrep -f GradleWrapperMain | wc -l`，全机少于两个重构建才开工，直接运行 Gradle `--max-workers=6`，不用 `nice`。此后恢复重任务规范。

安装器将构建产物、桥源码和 mathsvc 拷贝到 `~/Coach/service/releases/时间戳/`，在副本中建立独立 venv 和安装 Node 锁定依赖，才加载 LaunchAgent。临时 worktree 消失不会影响服务。安装失败时恢复上一个安装；历史 release 保留，供回滚。重新安装新 head 时先构建再执行同一 install 命令；安装不会覆盖数据。`installation.json` 记录当前 release；plist 保存实际运行环境。登录自启动由 LaunchAgent 管理，不能以手动 bootstrap 实测代替注销/重登录证据。

数据在 `~/Coach/data/`，备份在其 `backups/`；桥自己的工作目录在 `~/Coach/bridge/`。按照今晚「调用次数不设上限」，安装环境默认 `BRIDGE_DAILY_CLAUDE_CAP=0`、`BRIDGE_RATE_PAUSE_AT=1.1`，不再把旧的每日 60 次和 0.5 阈值写死进 plist；若另有授权，可在 install 前通过同名环境变量指定。本卡恢复检查不调用 AI；官方限流的兜底由 TON-1128 集成，当前源码既有的后台 0.8 保护仍在。服务只绑定回环和所配置的 Tailscale IPv4，端口 18791；桥只绑定回环 8791。所有账本 HTTP/WS 请求仍需要设备令牌。

## 停止、恢复和卸载

```sh
# 停止并 disable；下次登录也不会自行重启，直到 start：
python3 "$HOME/Coach/service/control.py" stop
# 重新 enable、加载，并检查认证接口和两个子进程：
python3 "$HOME/Coach/service/control.py" start
# 停止并删 LaunchAgent plist；数据、备份、日志、release 保留：
python3 "$HOME/Coach/service/control.py" uninstall
```

底层等价停止命令是 `launchctl disable gui/$UID/local.coach.server`，再执行 `launchctl bootout gui/$UID/local.coach.server`。卸载还需删除 `~/Library/LaunchAgents/local.coach.server.plist`。不要按进程名杀服务，不要手动删除数据库或令牌。卸载后重新执行源码中的 `install` 即可安装。

## 观察和复验

```sh
launchctl print gui/$UID/local.coach.server
python3 "$HOME/Coach/service/control.py" status
python3 tools/dev/check-service.py
python3 tools/dev/check-service-android.py
```

复验脚本会中断 Coach：依次杀桥、杀 mathsvc、挂起 mathsvc 验证健康恢复，再 SIGKILL Ktor 验证 LaunchAgent 恢复与旧子进程清理，最后 stop/start 验证停止与重启。只操作当前安装记录指向的进程，失败不借用其他端口实例。结果写 `.service-evidence/service.json`，不包含令牌、实际 IP、课程内容。

Android 复验先构建 debug 与 androidTest APK，再启动并清理自己拥有的 `emulator-5570`，让现有 `NetworkSecuritySmoke` 经已配置的 Tailscale IP 对常驻实例进行认证 HTTP/WS 检查。先完成恢复测试，再做 Android 测试，避免故障注入打断网络检查。它不启动替代服务，不修改 Tailscale。

`~/Coach/run/{bridge,mathsvc}.json` 是当前 JVM、清理守卫、真实服务 PID、健康状态和检查时间；JSON 原子更新。每个服务的 Python 守卫只负责进程组和父进程死亡清理，重启由 Ktor 决定。桥 liveness 是 `GET /health?scope=process`，避免 CLI 登录状态命令迟缓导致错误重启；完整 `/health` 仍提供 CLI 状态。mathsvc 使用自己的 stdin/stdout JSONL ping/pong。每十秒检查一次；死亡立即进入 1→60 秒指数退避，持续健康一分钟后复位。JVM 正常退出时等待清理；JVM 被 SIGKILL 后守卫自行清理桥、公式服务及已观察到的 CLI 进程组。

日志为 `~/Coach/logs/{server,bridge,mathsvc}-YYYY-MM-DD.log`，跨日第一次写入时换文件，各自保留当天和此前 13 日。桥请求元数据也进入轮转流，不记提示词或答案。JVM 初始化前的错误在 `bootstrap.log`；不分享含真实地址或账号的原始日志。检查健康不等于订阅可调用，CLI 登录状态另查完整桥 `/health`。

夜间备份与唤醒逻辑沿用账本实现；本卡还需在允许时段完成真休眠、JVM 单调钟实测、重启 Tailscale 后重绑、旧备份的发件箱额外保留策略。2026-11-02 15:00 GMT 前不做真休眠和 Tailscale 重启。
