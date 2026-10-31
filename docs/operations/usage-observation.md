# 复习流量保护与使用观测

生产桥 8791 不接受标记为测试的模型请求。测试必须用自己的桥、服务端端口和数据副本；
不能仅设置 `COACH_TRAFFIC=test` 后继续连 8791。详见 `tools/bridge/README.md`。

服务端正常启动后自动开启以下记录，双监听共用一个记录器：

- `COACH_LOG_DIR/access-YYYY-MM-DD.log`：UTC 时间、已认证设备、路由模板、HTTP 状态、总耗时、响应准备耗时。
  未认证请求的设备写 `unverified`。不写查询参数、请求/响应正文、令牌、配对码或自由形式错误。
- 同一访问文件的 `method=WS` 行：从本 socket 的导师操作到首段可见正文/公式的 `firstWordMs`。
  HTTP 的 `firstWordMs=null`，`responseReadyMs` 是响应就绪时间，不代表客户端显示时间。
- `COACH_STATE_DIR/diagnostics/usage.sqlite`：已认证设备上传的客户端事件，独立于学习账本，按 `(device,id)` 去重。
- 启动时及每 3600 秒原子更新 `COACH_STATE_DIR/usage-YYYYMMDD.md`。
  托管运行默认日志、状态目录分别为 `COACH_DATA_DIR` 的同级 `logs`、`state`；生产即 `~/Coach/logs`、`~/Coach/state`。非托管实例默认放在自己的数据目录内，避免临时实例互相写入。

客户端记录打开/回前台、页面和窗口宽度变化、学习动作、错误、断线/重连及可见首字。
动作和目标仅能使用共享代码里的封闭枚举；不记录知识点标题、输入、选项、答题内容、回答或异常原文。
讲解与追问都从 `TutorModel.onSent` 开始计时，进度帧不算首字。
自动 `tutor.open` 不冒充用户点按。

事件写入客户端 SQLite 独立队列（最多 5000 条，超出丢弃最旧记录），每 5 秒尝试批量上传。
请求超时/断网/旧服务端不支持上报时保留队列；重启后补发。每批最多 50 条，服务端最多接收 32 KiB，
收到成功回执后才移除。重试沿用事件 id，避免回执丢失时重复。队列与投递失败不阻断学习。
服务端保留最近约 14 天的诊断事件/访问文件。小结按收到日期归档，路径里的时间来自设备，
因此设备时钟偏差与跨日离线补发需要结合服务器接收日期判断。

验收（JDK 17；所有重构建走 `tools/dev/heavy`）：

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) tools/dev/heavy ./gradlew \
  :server:app:test :server:ai:test :shared:client:jvmTest \
  :androidApp:assembleDebug :desktopApp:compileKotlin --max-workers=2 --console=plain
tools/bridge/.venv/bin/python tools/bridge/tests/traffic_isolation.py
tools/bridge/.venv/bin/python tools/bridge/tests/fault_injection.py
tools/bridge/.venv/bin/python tools/bridge/tests/exam_protection.py
```

在模拟器连隔离服务端，从今天页打开知识点、讲解、回前台、改变窗口宽度；停止自己起的隔离服务端，
离线重启客户端，再恢复服务端。核对上报含 `open/screen/tap/disconnect/reconnect/first_word`，
断网期间事件补发，无相同 `(device,id)` 重复，访问日志和小结能还原路径。真实 CLI 的思考耗时需另测；
桩压测只证明测试请求不会争抢生产桥并发槽位，不能据此声称 Fold8 真实首字已提速。

生产部署须等 TON-1132 升级 main 完成、用户连续 15 分钟空闲，并完成 Scout 验收与 Architect 复核。
11 月 2 日 00:00–15:00 GMT 不部署、不预热。此变更需要服务端升级和客户端新包同时生效；
旧客户端没有自动补出此前未记录事件的能力。
