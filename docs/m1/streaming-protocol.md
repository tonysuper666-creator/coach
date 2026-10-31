# 导师流式链路（M1-01）：WS 帧与状态

M1-01 把三段接成一条链路：`server/app`（Ktor）↔ 桥副本 8791（`server/ai` 的 `BridgeClient.streamChat`）↔ `/v1/ws` 客户端（`shared/client` 的 `ChatStreamAssembler`）。这段协议是 DESIGN §6 的实现说明，不是新设计。

## 独立复跑 live 验收

先查看 `date`、`uptime`。2026-11-02 00:00–15:00 GMT 不运行构建与 live。重负载统一经 `tools/dev/heavy` 串行执行。

```sh
python3 -m venv tools/bridge/.venv
tools/bridge/.venv/bin/python -m pip install -r tools/bridge/requirements.txt
tools/dev/heavy ./gradlew liveTest --max-workers=2
```

Gradle 的不带项目路径的同名任务选择器也会执行既有 `server:ai:liveTest`（出题/批改验收）。只执行本卡可用 `tools/dev/heavy ./gradlew :liveTest --max-workers=2`，或 `:server:app:liveTest`；既有出题/批改入口保留原义。

前提：本机 Claude CLI 已登录，8791 空闲，真实课程页位于 `~/Coach/corpus/bridge-8791/*.md`（可用 `COACH_CORPUS_DIR` 指定）。缺语料明确失败，不跳过或伪造成功。

`LiveChatTest` 只在显式 `liveTest` 下执行，默认 `test/check` 不产生订阅调用。它使用真实 Ktor 路由、隔离账本与 WS 客户端，调用共享 `ChatStreamAssembler`；不启动 UI、不改变网络设置、不读写 8790 或 DeepTutor。每次调用前读取桥的当前计数及暂停状态，不重置计数、不改现役配置。2026-10-31 起按李政授权取消人为计次上限；`claude_cap=0`、`remaining=null` 表示无限，仍记录每次真实物理调用。测试自起的桥按该授权配置为 0 / 1.1；已有桥沿用其运行配置。

8791 已有服务时，先只读核对 PID、健康和空闲状态，再显式传 `COACH_LIVE_BRIDGE_PID=<现役桥PID>`。测试复用现役桥，结束仅记 `detach`，不会停止或重启该进程；未指定 PID 则仍要求 8791 空闲并自行起停。桩 CLI 回放及取消探针用本次测试拥有的随机回环端口与独立数据目录，不占用现役 8791。

验收客户端显式使用 `BridgeConfig(testTraffic = true)`，发送 `X-Coach-Traffic: test`，匹配现役桥独立的测试调用记账。普通客户端默认不带该标记；不把全局的交互调用差额算作本卡用量。

默认预计 **33 次真实 Claude 物理调用**：首字 20 次；关闭客户端 socket 10 秒后重连 10 次（回答可能仍在流式，也可能已结束）；流中取消 2 次（记录真实 CLI 及其子进程 PID，并检查 2 秒内退出）；真实 SSE 转发至首个正文 delta 后切断上游 1 次。首字从 `msg.ask` 发出计至共享消费器交付首个非空正文/公式，排除进度帧。续传逐条比较客户端与账本的 SHA-256。正常结束时逐条比较 `msg.end.status`（离线期间已结束则读完成快照）与 `BridgeClient` 从真实 `x_coach` 解析并记录的终态；主动取消/断流没有 `x_coach`，单独报告预期的 `cancelled/truncated`，不充当终态映射样本。

最后另起一次隔离端口的**桩 CLI 回放**（`replay`，不消耗真实订阅）：隔离账本与桩桥目录，注入协议关键词、跨 delta 的 `tool_` + `call`、无协议关键词的原始 JSON、查原文标记。断言共享客户端与落库正文都只留下合成的正常回答，丢弃内容写内部日志。桩计数与真实桥日计数分离；真实测试不覆盖 `BRIDGE_CHILD_PATH`。

另有 `early` 桩 CLI 取消回归：一组在收到 `msg.start` 后立即取消，一组在无正文的挂起桩 CLI 刚生成进程时取消，均要求 2 秒内无残留。单元测试还确定性覆盖会话先取消后注册句柄、future/body 先取消后发布，以及正文区间、集合、文献序号在所有切分位置和逐字 delta 下不丢字。原始 JSON 仅在源文本行首识别，已交出半行后不会把下一个 delta 的括号当成新 JSON。

逐条指标、散列、进程退出耗时和桥起停记录写入 `~/Coach/corpus/m1-stream/<run>/results.jsonl`。用 `COACH_LIVE_OUT` 指定独立的输出目录；账本、桥日志只留本地，不提交。报告采用 nearest-rank 分位数；延迟目标数字照实报告，功能、文本一致性和进程退出失败均使测试报错。

若桥仍声明有限额度，不足完整批次但足够首字组时，测试只跑首字组并明确记录其余项延期；不足首字组就失败。无限额度不套用该分支。分组续跑支持 `first,resume,cancel,cut,replay,early`；`COACH_LIVE_FIRST_N` 默认 20、`COACH_LIVE_RESUME_N` 默认 10，减少数量仅用于排障，不能作为完整验收。复核退回后的最小重跑为 `COACH_LIVE_STAGES=first,cancel,replay,early`（22 次真实调用）；每次运行写出实际调用数，不将未执行项计入通过。

2026-10-30 的执行证据分三批（本机目录 `~/Coach/corpus/m1-stream/`）：

| 批次 | 真实调用 | 结果 |
|---|---:|---|
| `ton1087-builder-20261030`，追加修复前 | 33 | 首字 n=20，p50/p90=1468/1673 ms；断网 10 秒续传 10/10 散列一致；取消 2/2 进程退出 16 ms；中断流 truncated；命令退出 0 |
| `ton1087-builder-final-20261030`，运行时代码 `459fd96` | 14 | 首字只抽检 n=1：1385 ms；续传 10/10；取消进程退出 17/12 ms；中断流 truncated。真实四组通过，随后桩回放因注入顺序与桥已有的 tool-call 截止规则冲突而令整条命令退出 1 |
| `ton1087-builder-replay-20261030`，只调整合成注入顺序 `a151b40` | 0 | `COACH_LIVE_STAGES=replay ./gradlew :liveTest` 退出 0；正常正文与结尾保留，协议及原始 JSON 泄漏 0 |

该批累计真实 `ai_calls` **47**（账本 `physical_calls` 合计与桥日计数相同）。这是历史批次：其 n=1 抽检不代替 n=20 验收，最终 head 的补测见下文。未测 Compose 绘制或真机屏幕像素；首字口径是共享客户端消费器交付的正文。

### 2026-10-31 复核退回后的修复验证

运行时代码 `b1433ed` 修复正文按 delta 边界误判为 JSON、取消先于句柄/future/body 发布的竞态，以及 V003 提前于 V002 的迁移顺序。新回归在旧代码上先复现 4 项失败；修复后模块测试 **70 条，0 失败**（ai 41、store 8、app 21）。输入原文的区间、集合、文献序号逐个切分位置及逐字分块都保持完整；fresh DB 首次打开即为 schema 3，重新打开不再迁移。

`COACH_LIVE_STAGES=replay,early tools/dev/heavy ./gradlew :liveTest --max-workers=2` 退出 0：协议/原始 JSON 泄漏 0，正常正文和结尾保留；`msg.start` 后立即取消、桩进程生成后但未出正文时取消分别在 **1016/999 ms** 确认无残留。该组 0 次真实调用，逐条数据在 `~/Coach/corpus/m1-stream/ton1087-reviewfix-final-stub-20261031/results.jsonl`。

真实组只到 **9/20**：8 条 complete、第 9 条 truncated(cli_exit)，随后 health 连接被拒绝，命令退出 1；真实取消组尚未执行。00:33:41 测试复用的现役桥为 PID 40830，00:34:25 断开时未执行 stop，之后监听已变为 PID 77702。此前还出现过 PID 72912 被替换为 40830，启动校验拒绝旧 PID，0 次调用。两次外部换版打断验收，未再尝试真实组，也不计算不满 n=20 的达标分位数。

上述中断批次真实 `ai_calls=9`、账本 `physical_calls=9`，不计算不满 n=20 的分位数。后续在最终 PR head `a40ffce` 上完成补测与独立验收（见 TON-1087 的 Builder-C、Scout、Architect 交付评论及 JSONL 附件）：

| 验证者 / revision | 首字 | 取消 / 回放 |
|---|---|---|
| Builder-C，`a40ffce` | n=20，p50/p90 **1218/1387 ms**；20/20 complete、非空、客户端与落库 SHA-256 一致 | 真实取消 15/13 ms；回放 0 泄漏；early 取消 1015/1003 ms |
| Scout 独立验收，`a40ffce` | n=20，p50/p90 **1241/1357 ms**，最大 10907 ms；20/20 complete、非空、散列一致 | 真实取消 13/12 ms；回放 0 泄漏；early 取消 1023/999 ms |
| Architect，`a40ffce` | 模块测试 70/70，独立探针确认三处修复 | 读码复核通过，未重跑 live；PR #13 合并为 `0d41533` |

Builder-C 的真实组与回放组分两条命令执行（22 次真实调用 + 0 次）；Scout 的 `first,cancel,replay,early` 在同一条命令退出 0（22 次真实调用）。续传 **10/10** 是在 `b9f4e06` 上独立跑出的证据，未在 `a40ffce` 重跑，不能写成最终 head 的新实测。

## 客户端 → 服务端（`/v1/ws`，与账本帧共用一条连接）

| 帧 | 字段 | 说明 |
|---|---|---|
| `msg.ask` | `threadId`、`courseId`、`text`、`pack`(可选，≤40K 字备课包) | 追问/问答，走 `TEACH` 流 |
| `msg.resume` | `msg` | 重连后要 `msg.snapshot` |
| `msg.cancel` | `msg` | 取消 = 服务端断开到桥的 HTTP 连接 |

## 服务端 → 客户端

| 帧 | 字段 | 说明 |
|---|---|---|
| `msg.start` | `msg`、`threadId`、`courseId`、`model`、`fallback` | 消息头；兜底时是一条新消息、`fallback=true`、`model` 写真实模型名 |
| `msg.delta` | `msg`、`i`、`text` | 可见正文；`i` 从 1 递增 |
| `msg.math` | `msg`、`i`、`tex`、`display` | 公式块，与正文共用 `i` 序号 |
| `msg.progress` | `msg`、`phase` | 目前只有 `thinking`（8 秒还没出正文），**不算首字** |
| `msg.snapshot` | `msg`、`i`、`text`、`status`、`reason` | 重连先发它，再接后续 delta |
| `msg.end` | `msg`、`status`、`reason`、`chars` | 终态 |
| `msg.error` | `msg`、`error` | 请求字段缺失等客户端错误 |

## `(msg, i)` 去重与缺号恢复

`msg.delta` 与 `msg.math` 共用一条从 1 开始的序号。`ChatStreamAssembler` 只追加 `i == lastIndex + 1`，丢弃 `i <= lastIndex` 的重复帧。遇到缺号时不追加该帧、不推进游标，调用 `onResumeRequired(messageId, lastIndex)`；调用方应立即发送 `{"t":"msg.resume","msg":messageId}`。回调可能重复，调用方可合并进行中的续传请求。当前 `TutorModel`/`TutorClient` 的在线回调接线由 TON-1134 承接（超出 TON-1129 文件范围）；本接口和桩端到端验证不代表真机已经自动续传。

`msg.snapshot` 是权威全文，整体替换正文和游标，清除缺号状态；比当前连续游标更旧的快照仍忽略。缺号尚未恢复，或 `msg.end.chars` 与本地正文长度不符时，消费器请求续传并暂不交付 `Ended`，避免残缺正文被标成完成。终态快照可直接恢复最终正文和状态。

服务端在开始处理 WS 入站命令前同步建立导师帧订阅，`msg.resume` 的快照生成与发送和该连接的 delta 共用互斥锁。`ChatHub.tryEmit` 缓冲满失败记 `COACH chat-frame-drop`（消息、类型、序号、原因，不含正文）。

## 终态

`msg.end.status` ∈ `complete / truncated / failed / cancelled`，与桥 `x_coach.status` 一一对应（DESIGN §4.2、§6）：

- 桥 `complete` 且过滤后有非空白可见正文 → `complete`；空白正文 → `failed(reason=empty_visible_body)`，并写终态日志；缓存开讲与非流式兜底同样适用；
- 桥 `truncated` → `truncated`；
- 桥 `failed`（一个字都没出）→ `failed`；已经出过字再失败按 `truncated` 收（保留文字、给「重试」）；
- 收不到终态帧 → `truncated`（ADR-0002）；一个字都没有 → `failed`；
- 用户取消 → `cancelled`；唤醒收尾 → `truncated(reason=suspended)`。

`reason` 增加 `empty_visible_body`（过滤后无可见正文）；其余透传桥的 `timeout / cli_exit / max_output_tokens / client_gone`，服务端自己判定的补充 `first_token_timeout`（25 秒）、`stall`（30 秒无 delta）、`total_timeout`（180 秒）、`cancelled`、`suspended`。

## 超时（全部单调钟，DESIGN §4.7）

| 期限 | 值 | 行为 |
|---|---|---|
| 「还在想」 | 8 s | 发 `msg.progress`，不算首字 |
| 首字截止 | 25 s | 断开桥，原消息 `failed(first_token_timeout)`，再显式调 Codex 非流式，新开一条 `fallback=true` 的消息写真名 |
| 无新可见正文 | 30 s | `truncated(stall)` |
| 单条消息总时长 | 180 s | `truncated(total_timeout)` |

「还在想」、首字截止和停滞计时以过滤后真正发布的非空白正文/公式为准；内部 JSON、协议行、查原文标记、纯空白不算首字，也不延后首字截止。桥侧 `ai_calls.ttft_ms` 保持桥原始 delta 口径，不用它冒充客户端首字。疑似 JSON 的行首要等到行尾或 EOF 才能判定，因此这类前缀会短暂缓冲。

进行中的消息每秒把「已可见正文 + `i`」写进 `messages`（`status=streaming`，迁移 V003 加的 `delta_index`），所以重连时的 `msg.snapshot` 最多落后一秒，且在线的消息直接从内存取、不落后。

## 可见流过滤（§5.8 第 4 条）

`server/ai/VisibleStream` 在落库前做确定性过滤：`【需查原文:…】` 标记剥掉；命中 `placeholder`、`malformed`、`tool_call`、`<tool_calls>`、`x_coach`、`finish_reason` 的整行丢弃并记 `COACH visible-drop` 日志；只有带类型的内容块（正文、公式）进入 `msg.delta` / `msg.math` 与 `messages.content_md`。过滤按「安全前缀」吐出，一个 token 跨 chunk 被切开也不会漏出。

原始 JSON 只在行首识别，且整个 JSON 块闭合后直到行尾/EOF 只剩空白才丢弃（支持格式化多行 JSON）；`[0, 1] 是效用的取值范围。`、`[1] Drummond 2015。` 等后接正文的前缀完整保留。
