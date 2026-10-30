# Coach 订阅桥副本（8791）

把订阅制 CLI（Claude Code / Codex，可选 agy）包成本机 OpenAI 兼容接口，给 Coach 用。
它是 `~/DeepTutor/tools/subscription_bridge.py`（8790，DeepTutor 在用）的副本，按 ADR-0002 决定 1 的九项改写。
**8790 与 DeepTutor 的运行文件一个字都不动**：副本有自己的端口、自己的目录、自己的 venv，日志也不会写进 DeepTutor。

## 跑起来

```sh
python3 -m venv tools/bridge/.venv
tools/bridge/.venv/bin/python -m pip install -r tools/bridge/requirements.txt
tools/bridge/run.sh          # 前台，Ctrl-C 停；M0 不装 LaunchAgent
curl -s localhost:8791/health | python3 -m json.tool
```

- 端口：`BRIDGE_PORT`，默认 **8791**。
- 运行数据：`COACH_BRIDGE_HOME`，默认 `~/Coach/bridge`（`logs/bridge.log`、`work/{claude,codex,agy}-cwd`、`work/tmp`）。
- venv：`tools/bridge/.venv`，版本锁在 `requirements.txt`（与 8790 同版本），**不借用 DeepTutor 的 venv**。

## 契约

### 请求头

| 头 | 作用 |
|---|---|
| `X-Request-Id` | 回显进响应头与日志，也进 `x_coach.request_id`；不带就生成一个 |
| `X-Coach-Traffic: test` | 测试与 liveTest 必带；只有测试受每日上限和暂停线约束。无标记为用户学习，照常记物理调用。 |
| `X-Coach-Fallback: off\|on` | **默认 off**：不偷偷换模型，失败就如实报失败。`on` 时才由桥兜底到 `gpt-6.1-sol`，`physical_calls` 照实累加 |
| `X-Coach-Effort: low\|medium\|high\|xhigh\|max` | 按请求设推理强度（也可用 body 的 `reasoning_effort`）。Claude 走 `--effort`，Codex 走 `model_reasoning_effort`，agy 走 `--reasoning-effort` |
| `X-Coach-Background: 1` | 后台任务：最多占 1 个槽；期中前一律转派 Codex；`rate_limit_event` 显示紧张时直接 429 |

### 响应

非流式：`model` 字段是**实际出答模型**（`served_by`），另有 `X-Served-Model` 头与顶层 `x_coach`。

流式：`[DONE]` 之前的**最后一帧**带 `x_coach`：

```json
{"status":"complete|truncated|failed","reason":null|"timeout"|"cli_exit"|"max_output_tokens"|"client_gone",
 "finish_reason":"stop|length|error|tool_calls","served_by":"...","fallback":false,"physical_calls":1,
 "request_id":"...","requested_model":"...","queue_ms":0,"ttft_ms":1200,"elapsed_ms":3300}
```

Coach 只把 `status==complete` 记为讲解完成；收不到这一帧按截断处理。
**流式请求一律以终态帧结束**（HTTP 200），上游失败也是 `status=failed` 的终态帧，不用 HTTP 状态码表达；
HTTP 4xx/429 只留给请求本身的问题（坏 JSON、未知模型、提示词超长、额度紧张时暂停后台）。
中途失败时 `finish_reason` 只会是 `length`（max_output_tokens）或 `error`，**不会再发 `stop`**。
终态判定按 Claude `result` 的 `is_error` / `terminal_reason` / `api_error`（真实 max_output_tokens 是 rc=1 + `api_error=max_output_tokens`，先看 `is_error` 再看 rc）。

### `/v1/models` 与 `/health`

- `/v1/models` 每个模型带 `true_stream`：Claude `true`，Codex `false`（`exec --json` 没有 token 级 delta），agy 默认不注册。
  界面按 `true_stream` 决定显示「等待中」还是打字动画；对 `false` 的后端发 `stream=true` 时桥只是把整段切块发出。
- `/health` 返回每家 CLI 的登录状态（只调 `claude auth status` / `codex login status`，**不读凭证文件**，结果缓存 60 秒）、
  模型能力表、当前并发（`active`/`foreground_waiting`）、额度信号（`rate_limit`）与后台转派设置。

## 并发与额度

- Claude 上限 `BRIDGE_CLAUDE_CONCURRENCY`，**期中前默认 1**（ADR-0002 裁决；期中后按设计改 2）。
- 后台任务最多 1 个（`BRIDGE_BACKGROUND_SLOTS`），且**排在所有前台请求之后**：他的复习不能被 Coach 的后台预生成挤到。
- 读到 `rate_limit_event` 且 `utilization≥0.8` 或 `status≠allowed` → 后台请求 429（`Retry-After: 900`），用户前台照常。
- `BRIDGE_BACKGROUND_BACKEND=codex`（默认）：后台请求直接转派 Codex，转派后的模型名照实写进 `served_by`，响应头带 `X-Coach-Routed-From`。

## 计时口径

- 首字/总时长超时**从拿到信号量之后起算**；排队时长单独记 `queue_ms`（`x_coach` 与日志都有）。
- 出字前 `BRIDGE_CLAUDE_TIMEOUT`（默认 90 秒）；出字后总上限 `BRIDGE_STREAM_TOTAL_TIMEOUT`（180 秒）；
  已有文字后 `BRIDGE_STALL_TIMEOUT`（30 秒）没有新事件判卡住。
- 取消或断开：杀整个 CLI 进程组，`reap()` 等它真的退出（回归里实测 2 秒内 `pgrep -f "claude -p"` 回到基线）。

## 沙箱

`bin/{codex,agy,grok}` → `sandbox/sandboxed_agent.sh` → 由桥渲染的 `$COACH_BRIDGE_HOME/work/agent_sandbox.sb`。
SBPL 模板是 `sandbox/agent_sandbox.sb.in`（`@@HOME@@` 占位），启动时渲染 —— 仓库里不留本机绝对路径（DESIGN §12.1）。
白名单从 DeepTutor 版复制后重审过（见文件头注释）：去掉 `~/DeepTutor/library` 的读权限（备课包走提示词，CLI 不需要翻课程文件）、
`~/Coach` 默认全禁只放行 `~/Coach/bridge/work`。Claude 不走沙箱，与 8790 现役行为一致（`--tools "" --strict-mcp-config`，无工具面）。

## 期中保护（2026-10-29 收紧，至 11-02 15:00）

| 规则 | 落地 |
|---|---|
| 每个请求前都查 DeepTutor 是否在忙 | `tests/live_probe.py` 的 `preflight()` 每个请求前跑一次，最后一行 10 分钟内有活动就**中止整批**（不是只在批首查） |
| Coach 的 Claude 测试物理调用 ≤150 次/天（与 TON-1041 合计） | 桥内硬上限 `BRIDGE_DAILY_CLAUDE_CAP`（默认 150，只计 `X-Coach-Traffic: test`），计数在 `$COACH_BRIDGE_HOME/state/daily.json`，超了直接 429 `daily_claude_cap`，不 spawn；同一天还有别的卡在跑就把 cap 调成剩余额度 |
| `rate_limit_event` utilization ≥0.5 暂停测试调用 | `BRIDGE_RATE_PAUSE_AT`（默认 0.5）：测试前台后台一起 429 `rate_limit_paused`；后台另有 0.8 的更严阈值 |
| 11-01 18:00 – 11-02 15:00 不跑任何 Claude live | `live_probe.in_blackout()` 直接拒跑（Codex live 不受限） |
| 8791 只在测试时段手动起，测完按 PID 停 | `live_run.py` 起停各写一行 `stage=bridge` 记录（含 pid 与时间），报告里据此列起停时间；不装 LaunchAgent |

## 测试

```sh
# 桩 CLI 故障注入（不花额度）：kill -9 中途、挂起超时、拒答兜底开/关、max_output_tokens、4 请求排队
tools/bridge/.venv/bin/python tools/bridge/tests/fault_injection.py

# live（真实 CLI + 真实教材页；每个请求前都只读检查 DeepTutor 的 bridge.log，忙就中止整批）
# 语料：把教材页放进 ~/Coach/corpus/bridge-8791/（或用 COACH_CORPUS_DIR 指过去），仓库里不放语料
tools/bridge/.venv/bin/python tools/bridge/tests/live_run.py --stages claude --claude-n 20
tools/bridge/.venv/bin/python tools/bridge/tests/live_run.py --stages codex,codex-stream,cancel --codex-n 5
```

live 结果逐条写 `--out`（默认 `/tmp/coach-bridge-live.jsonl`）。

## 还没做

- **Grok**：`grok` 还没 live 实测，按 ADR-0002(8) 不注册为导师；接的时候加一个 `call_grok` + 注册表条目即可（`--reasoning-effort` 已在参数表里）。
- **agy**：默认关（`BRIDGE_AGY_ENABLED=0`）；条款风险未定前不打开。
- **超时/兜底的客户端侧**（25 秒首字截止、8 秒「还在想」、显式调 Codex）在 Coach 服务端，不在这座桥里。

## M0 实测（2026-10-29，真实 CLI + 真实教材页）

原始逐条数据：`tests/results/live-20261029.jsonl`（每行一个请求）。

| 项 | 结果 |
|---|---|
| Claude 流式追问首字（备课包 40,077 字，逆向选择章 08→09，冷缓存，n=20） | p50 **2068 ms**、p90 **4280 ms**、max 4737 ms、min 1506 ms；排队 ≤2.5 ms → **达到** p50≤5s / p90≤8s |
| 同协议但备课包只含 1–2 章（问题在材料里答不了，n=20） | p50 6816 ms、p90 9525 ms → 说明「包里有没有答案」比包大小更影响首字，与 d3 结论一致 |
| Codex 非流式（n=5） | 全部 complete，7.2–12.2 s（p50 9.3 s），`served_by=gpt-6.1-sol` |
| 对 Codex 发 `stream=true`（n=3） | 每请求 3 个 chunk，首末块间隔 0.0–0.1 ms → 确认不真流式（`true_stream=false`） |
| 流到一半断开（n=2） | `pgrep -f "claude -p"` 立即回到基线（0 次轮询即已退出），日志 `status=truncated reason=client_gone` |
| 桩 CLI 故障注入 | 51 项断言全绿（`tests/fault_injection.py`） |
| 8790 与 `subscription_bridge.py` | 全程 PID 70368、shasum `ef98bb7…` 未变 |

8791 起停（每次都是 `live_run.py` 起、跑完按 PID 停）：20:00 起–20:05 停（33 个请求）、20:21 起–20:24 停（20）、20:24 起–20:25 停（1，改完再验一次）。
当天 Claude 物理调用 43 次（上限 60），`state/daily.json` 已按实际数记为 43（计数器是这批跑完之后才加的）。

本卡物理调用：Claude 20+20+2（取消）+1（改完再验一次）=43，Codex 5+3=8，合计 **51**。
（`bridge.log` 里有 3 行 codex-stream 重复，是 7477290 修掉的双记日志，不是多出来的调用。）
