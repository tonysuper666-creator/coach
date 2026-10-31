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

累计真实 `ai_calls` **47**（账本 `physical_calls` 合计与桥日计数相同），保护参数仍为 **60 / 0.5**，当日余额 **13**。模块测试 **65 条，0 失败**。最终运行时的 n=20 首字分位数**未测**：不能用修复后 n=1 抽检代替，留待验收者在日额度恢复后独立复跑。未测 Compose 绘制或真机屏幕像素；本卡首字口径是共享客户端消费器交付的正文。

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

## `(msg, i)` 去重

`msg.delta` 与 `msg.math` 共用一条序号；客户端丢掉 `i <= 已收最大 i` 的帧。`msg.snapshot` 把游标重置为它带来的 `i`，所以重连后服务端重发的旧序号会被丢弃，正文不会重复也不会缺口。

## 终态

`msg.end.status` ∈ `complete / truncated / failed / cancelled`，与桥 `x_coach.status` 一一对应（DESIGN §4.2、§6）：

- 桥 `complete` → `complete`；
- 桥 `truncated` → `truncated`；
- 桥 `failed`（一个字都没出）→ `failed`；已经出过字再失败按 `truncated` 收（保留文字、给「重试」）；
- 收不到终态帧 → `truncated`（ADR-0002）；一个字都没有 → `failed`；
- 用户取消 → `cancelled`；唤醒收尾 → `truncated(reason=suspended)`。

`reason` 透传桥的 `timeout / cli_exit / max_output_tokens / client_gone`，服务端自己判定的补充 `first_token_timeout`（25 秒）、`stall`（30 秒无 delta）、`total_timeout`（180 秒）、`cancelled`、`suspended`。

## 超时（全部单调钟，DESIGN §4.7）

| 期限 | 值 | 行为 |
|---|---|---|
| 「还在想」 | 8 s | 发 `msg.progress`，不算首字 |
| 首字截止 | 25 s | 断开桥，原消息 `failed(first_token_timeout)`，再显式调 Codex 非流式，新开一条 `fallback=true` 的消息写真名 |
| 无新 delta | 30 s | `truncated(stall)` |
| 单条消息总时长 | 180 s | `truncated(total_timeout)` |

进行中的消息每秒把「已可见正文 + `i`」写进 `messages`（`status=streaming`，迁移 V003 加的 `delta_index`），所以重连时的 `msg.snapshot` 最多落后一秒，且在线的消息直接从内存取、不落后。

## 可见流过滤（§5.8 第 4 条）

`server/ai/VisibleStream` 在落库前做确定性过滤：`【需查原文:…】` 标记剥掉；命中 `placeholder`、`malformed`、`tool_call`、`<tool_calls>`、`x_coach`、`finish_reason` 的整行丢弃并记 `COACH visible-drop` 日志；只有带类型的内容块（正文、公式）进入 `msg.delta` / `msg.math` 与 `messages.content_md`。过滤按「安全前缀」吐出，一个 token 跨 chunk 被切开也不会漏出。
