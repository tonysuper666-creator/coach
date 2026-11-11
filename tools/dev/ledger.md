# 账本、发件箱与回档复验

所有输入均为明确标记的合成课程；测试不连接 DeepTutor，不运行任何 AI CLI。真实库、作答、令牌与日志都不入仓库。

```sh
tools/dev/check-ledger.sh
python3 tools/dev/check-ledger-android.py
COACH_VERIFY_LEDGER_WAKE=1 COACH_SKIP_SERVE=1 python3 tools/dev/check-android.py
```

第一条运行 common 领域黄金用例、10,000 组随机排列/重复/晚到复核的字节一致性测试、3,000 组序列逐前缀的掌握不变量测试、SQLite 幂等/备份/栅栏测试和真实 HTTP/WS/JVM 客户端端到端测试。后者按顺序：

1. 创建旧备份，再产生题包；其中一题在备份里不存在。
2. 在线提交并确认 1 答，停服务，离线落库 5 答，关闭并重开 SQLDelight 客户端。
3. 重启服务，确认全部 6 答；保留尚未被备份覆盖的全部命令原字节。
4. 停服务并恢复旧备份，生成新 epoch，重启；客户端自行检测、清副本、拉快照、重放。
5. 核对 6 条原答、0 丢失、0 重复首答；缺题的 1 条为可见孤儿作答，不计分。客户端与服务端投影字节一致。
6. 再同步不多写事件；新备份成功覆盖后才删保留命令。

另有连续两次回档回归：手机确认一答后离线，服务端先恢复旧备份，再写入其他事件并备份，使新库 seq 超过这条旧确认 seq，然后再恢复。手机重连后仍须找回该答、保留原命令字节且不重复；已经包含在备份中的保留命令须返回 DUPLICATE。新纪元成功备份覆盖后，发件箱才正常清理。

另一测试对真实 Ktor 子进程组 STOP 32 秒再 CONT，检查消息 truncated/suspended、running job 重新排队且 fence 增大、客户端自行重连。另一个 Android 仪器测试通过相同的 32 秒 STOP/CONT，要求手机端 SyncEngine 实际先观察到 offline，再自行重连到 online。它们覆盖进程调度间隔检测；没有调用系统休眠，因而不能推断物理休眠期间 macOS 单调钟是否前进。未动 Tailscale。

Android 脚本先构建，再用同一把重任务锁启动自己的 API 35 模拟器，安装 APK、运行 50,000 事件投影基准并收集耗时，最后按 PID 关掉自己启动的模拟器。输出在 `.ledger-evidence/`，不入 Git。完整重放数字不包含生成合成事件、磁盘读取或 JSON 反序列化；缓存数字包含重新打开 Android SQLite、读取游标、读取缓存和首次解析投影 JSON。测试先将 50,000 条真实 SQLite 记录和投影缓存写入数据库，先测连接重开，再 force-stop 应用，在全新 instrumentation 进程中测一次缓存冷开并核对投影哈希。模拟器性能不能代表手机真机。

规则按 DESIGN §2.2 的最近 3 次有效首答实现：错、对、对、对的最后三条已经满足通过。任务卡里「错对对对不过」与此不一致；测试明确使用设计正文语义，未修改设计。未复核、LEGACY、提示和同题重答均不能补足窗口。构答复核晚到时，在复核事件处锁存。两条严格证据先达到稳固时，也会锁存已通过；之后合格情境失败显示曾稳固、需回炉，仍保留已通过，只有支撑证据本身失效才撤销。稳固可用一条闭卷构答 + 次日一条 TIMED 单选证明一般策略；M1 普通选择题不会提供稳固证据。

## 接口和持久化

`SyncEngine` 接收平台 SQLDelight driver、设备 ID、ID 生成器、SHA-256 函数、HTTP transport。`enqueue()` 完成数据库插入后才可锁定题卡、显示本地反馈；客户端必须保留返回的命令原始字节和哈希。`start(scope)` 自动同步和重连；`syncOnce()` 可供人工复验。新 ID 应由设备生成，ULID 可用 domain 的纯函数 `ulid(timestampMillis, entropy)`。客户端本地数据库必须放应用持久数据目录，不能放缓存目录。

`WireCommand.payloadBytes` 是原始 JSON UTF-8 字符串；外层 JSON 的转义不改变哈希输入。服务端不重序列化载荷来比较 command_log。回执含 epoch、backedUpThroughSeq、eventSeqs；冲突返回 HTTP 409 和已入账原答/判分，失败设备自己的原文作为记录保留。

确认 seq 只在所属 epoch 内有意义，不能跨纪元比较。换纪元时，客户端先将所有仍保留且非 REJECTED 的命令重置为待重放，再应用新纪元的备份水位；已在恢复库中的命令由 commandId 去重。即使旧确认 seq 小于等于新库 `lastSeqBefore`，也必须重放，否则手机错过连续多次回档时会丢答。这与当前 DESIGN §4.3「只重放 seq 大于 lastSeqBefore」的字面条件不同，设计修订应将该句同步改成「重放全部仍保留的非 REJECTED 命令」，保留同段按 commandId 去重的规定；此次未改设计正文。

`GET /v1/hello`、`GET /v1/courses/{c}/events?after=&limit=`、`GET /v1/courses/{c}/snapshot`、`POST /v1/commands`（单个或数组）与 `WS /v1/ws` 都要求 `Authorization: Bearer ...` 和 `X-Coach-Device`。令牌与设备绑定。WS 发 epoch 与 ledger 增量，重连可发 `{"t":"hello","epoch":"...","ledgerAfter":{"course":42}}`。REST 增量使用全库游标，课程序号有空洞是正常的。

Ktor 的组合根只持有一个 `LedgerActor`；命令、备份、租约、job 领取与结果提交都经它串行化。内部出题/复核服务可在 actor 中调用 `append`、`registerQuestion`、`enqueueJob`。`commitJobResult` 在同一事务内比较 `(epoch,fence)`、questionVersion、answerHash，再终结 job 并写事实；不得在 token 失效后另写事实。发件箱不接受客户端自报已通过或已稳固。

### 聊天原文与恢复

`msg.ask` / `quick.ask` 接收 `clientMsgId`；当前共享客户端每次发送生成一个 UUID。重发同一次发送必须保留这个 ID。服务端以认证设备 ID + `clientMsgId` 去重，先在同一事务保存 `role=user`、原始文字（包括首尾空白）、服务器接收时间与线程，再分派进度、继续或模型回答。确认帧 `msg.user` 带服务器消息 ID、`clientMsgId`、`deviceId`、`createdAt` 和原文；客户端用这组标识合并本地显示。相同 ID 改文字、课程或线程会拒绝；相同文字配新 ID 是一次新的发送。旧客户端省略 ID 仍能保存，但无法识别其重新提交是否为同一次发送。

重新 `tutor.open` 会先返回线程的 `msg.history`，也可直接发送 `{"t":"msg.history","courseId":"course","threadId":"thread"}`。历史按 `mseq` 排列，用户消息为 `msg.user`，助手消息为带角色、时间、模型与状态的 `msg.snapshot`；正在生成的内容从内存游标取最新正文。界面恢复历史后再判断是否需要自动开始讲解。重复历史及确认不会重复显示用户消息。纯文字继续和进度查询也保留用户原文，不因此新增模型调用；按钮生成的内部提示不伪装成用户发言。模型上下文排除当前已保存的用户消息，避免把它重复传入两次。

V006 迁移前先 `VACUUM INTO` 留副本，并保存固定覆盖起点 `userTextCoverageSince`。包含迁移前消息或线程事件的线程返回 `legacyHistoryUncovered=true`，界面提示早期对话不完整；不根据旧状态事件补造提问。发送确认只表示原文已保存；若进程在保存后、分派回答前退出，重发同 ID 仍只确认原文，不自动重做导师动作。聊天未接入离线发件箱，未收到确认的输入不能保证已送达。

合成验收（桩桥，无真实 CLI、生产库或 DeepTutor）：

```sh
tools/dev/heavy ./gradlew :server:store:test :server:app:test :shared:client:jvmTest :desktopApp:compileKotlin :androidApp:compileDebugKotlin --max-workers=2 --console=plain
tools/dev/heavy ./gradlew :shared:ui:jvmTest --tests com.coach.ui.ComposerPhoneTest --max-workers=2 --console=plain
```

`ChatFlowTest` 通过真实认证 WS 验证普通追问、进度、纯文字继续、临时问答，关闭重开服务后在另一配对设备恢复，并查询 `VACUUM INTO` 副本逐字核对用户原文、角色、时间和线程。`ChatPersistenceTest` 验证 V005 迁移不伪造缺失原话，重启后去重和覆盖起点保持；`TutorModelTest` 验证本地显示、确认及重复历史合并。

未知事件/未知 schema 保留原文；领域结果标记 `requiresServerSnapshot`，UI 应使用服务端快照并提示更新。投影版本常量变更后须重新计算。

Android 完整重放超过 800 ms 时启用的 `projection_cache` 以课程、投影版本、账本游标和 epoch 校验。新纪元清副本时同时清缓存；新事件使游标不匹配，后台同步重新 fold 后再发布 revision。没有变化时冷启动直接读持久投影；有新尾部时仍作全量 fold，并不宣称实现了增量累加器快照。UI 应观察 engine 的 revision，读取已备好的视图；`start()` 的同步与重算运行在 Default dispatcher。服务端命令把事件、更新后的投影缓存、command_log 回执在同一事务提交，内部事实追加会使缓存失效。

## 手动起停与回档

```sh
tools/dev/heavy ./gradlew :server:app:installDist --max-workers=2
python3 -m venv tools/bridge/.venv
tools/bridge/.venv/bin/python -m pip install -r tools/bridge/requirements.txt
npm ci --prefix tools/mathsvc
tools/dev/run-all.sh
# 另一终端按记录的 supervisor PID 关闭本组：
tools/dev/run-all.sh stop
```

脚本前台运行，三个子进程都 nice 15；日志在 `~/Coach/logs/`，PID 与启动时间在 `~/Coach/run/run-all.json`。Ctrl-C 或 stop 会终止本组进程并等待回收。已有清理句柄时拒绝覆盖；确认其 PID 已退出后才能移除残留句柄。M0 不安装常驻任务，不做后台保活。

运行数据默认 `~/Coach/data/`。可用 `COACH_DATA_DIR`、`COACH_RUN_DIR`、`COACH_LOG_DIR`、`COACH_TOKEN_FILE`、`COACH_DEVICE_ID`、`COACH_PORT`、`COACH_TAIL_HOST` 隔离实例。首次启动生成仅本人可读的 `device-token` 并注册本机设备；完整配对 UI 在后续卡接入。不要把令牌放 URL、Git、截图或分享日志。

备份使用 VACUUM INTO + 不可变 blobs 硬链接 + 含 messages 的 events.jsonl；只有全部写完才生成 complete 并发布 backedUpThroughSeq。每次首次结构迁移前另留数据库副本；后续结构版本须沿同一编号迁移入口扩展。启动/唤醒补做过期备份，03:30 之后检查当日备份，自动快照保留 14 日与再前 8 周的周快照。

先停服务，带同一数据目录执行：

```sh
COACH_DATA_DIR="$HOME/Coach/data" server/app/build/install/app/bin/app restore "$HOME/Coach/data/backups/<snapshot>"
```

恢复前检查备份完整标记和 SQLite integrity_check，当前数据库与 blobs 移到同目录的 before-restore 安全副本。旧 queued/running job 置 cancelled 后另行排队，清空租约，生成新 epoch 和 server_restored。设备只重放自己的命令；永远不再上线的设备仍无法补回自己的答题。这是设计的已知边界。

已知边界：如果最新备份损坏而选择更早的备份，两份备份之间的已确认命令可能已经按较新的水位从发件箱删除，无法从该设备补回。M1 可评估延长已备份命令的保留时间，本次不改变保留策略。另在未知 attempt、题目仍在库时，补写 posed 的 kpIds 和 purpose 来自客户端快照，format 和 keyState 取服务端；单用户 M0 接受此边界，M1 的 question_kp 可用后应从服务端校验并取值。题目也不在库时则只保留孤儿作答、不计分。

## 本机测量（2026-10-29）

初次交付 head `09c9a29` 的 22 个 JVM 测试通过，含 10,000 组随机用例；真实 HTTP/WS 回档演练 6/6 答找回、零丢失、零重复首答。Android 网络测试 3 项通过，系统 SQLite 3.44.3；Android SyncEngine 也实际经历了服务端挂起 32 秒，先观察到 offline，再自动重连至 online。下面数字来自 sable-api35（API 35、2 cores、nice 15），不含事件构造和进程启动时间：

| 测量 | 耗时 |
|---|---:|
| 50,000 事件首次纯投影 | 4,351 ms |
| 同进程关闭并重开 SQLite 后读缓存 | 51 ms |
| force-stop 后新进程读缓存并首次解析 | 96 ms |

两种缓存读取都与完整投影字节/哈希一致；首轮未加缓存的测量为 3,133 ms。测试打印实际读数，未用计时断言让慢机器失败。32 秒 STOP/CONT 与三进程按 PID 起停均已真实运行；没有进行物理系统休眠，没有修改 Tailscale 配置。桥仅在隔离 18798 端口做 health 检查，live AI / 桥物理调用数为 0。

本轮复核修复的 `tools/dev/check-ledger.sh` 实测 25 个 JVM 测试、0 失败、0 跳过。连续两次回档补回答案与备份内命令去重两种回归均通过，原 HTTP/WS 演练仍是 6/6、零丢失零重复。新增不变量测试生成 3,000 组序列，检查 76,791 个前缀；有效性事件之外撤销通过、复习撤销通过、提示/assisted 进入稳固锁存、稳固但未通过，违规计数均为 0。Android/JVM 共享客户端构建通过；本轮未重测模拟器耗时，上表保留原测量的 head 与来源。
