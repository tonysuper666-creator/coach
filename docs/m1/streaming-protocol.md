# 导师流式链路（M1-01）：WS 帧与状态

M1-01 把三段接成一条链路：`server/app`（Ktor）↔ 桥副本 8791（`server/ai` 的 `BridgeClient.streamChat`）↔ `/v1/ws` 客户端（`shared/client` 的 `ChatStreamAssembler`）。这段协议是 DESIGN §6 的实现说明，不是新设计。

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
