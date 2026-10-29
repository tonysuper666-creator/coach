# 出题与批改的真实端到端：M0 报告（TON-1041）

只放数字与结论。课程原文、108 题全文、他本人的作答都在本机语料目录（`~/Coach/corpus/gradegen/`），不进仓库。

## 结果：部分达到

| 判定项 | 目标 | 实测 |
|---|---|---|
| 植入错键题被盲复核拦下 | 5/5 | **5/5 拦下**，对照组 1 道干净题 0 误报 |
| 植入的非原文证据 / 截断证据 | 全部拒绝 | 单元用例覆盖：改写、丢负号、截数字、词内截断全部拒绝；他的真实作答 18/18 引文全部通过（误拒率 0/18） |
| 他 5 条真实中文作答过逐字校验 | 5/5 | **5/5 条都有可核验证据**，18 条引文 18 条通过 |
| 每道已复核题的物理调用数 | ≤1.5 | **0.42**（25 次物理调用 / 59 道已复核题） |
| 补一批 3 道的耗时 | p50 ≤25 秒 | **≈30.1 秒，未达到**（生成 28.5 秒是瓶颈，见下） |
| 答错一次后下一题等待 | ≤10 秒（有缓冲） | **0 毫秒**（题池还剩 2 道已复核题，直接取） |
| ≥20 个 KP 跑「生成 + 盲复核」 | ≥20 | **20 个 KP、60 道题**，59 道通过，弃用率 1.7% |
| 108 题里的 60 道选择题逐批盲复核 | 60 | **60 道、6 批**，一致 55 道（91.7%） |
| 构答题（带冻结 rubric + 异厂复核） | 至少 1 个 KP | 1 个 KP 出 2 道，rubric 异厂复核 CONFIRM |

## 数字

### 调用（`ai_calls` 形状，共 38 条）

| task | 次数 | 模型 |
|---|---|---|
| `gen_mcq`（出题） | 20 | gpt-6.1-sol |
| `blind_review`（4 个 KP 一批盲复核） | 5 | claude-sonnet-5-5 |
| `blind_review_existing`（10 道一批） | 6 | claude-sonnet-5-5 |
| `blind_review_planted` | 2 | claude-sonnet-5-5 |
| `gen_constructed` / `review_constructed_key` | 1 / 1 | gpt-6.1-sol / claude-sonnet-5-5 |
| `grade_constructed` / `cross_review_grade` | 2 / 1 | claude-sonnet-5-5 / gpt-6.1-sol |

Claude 16 次、Codex 22 次；当日桥的 Claude 计数从 43 到 59（硬上限 60，未触发）。

### 出题 + 盲复核（20 个 KP，每个 KP 3 道）

| 项 | 值 |
|---|---|
| 生成耗时 p50 / p90 / max | 28.5 s / 39.6 s / 72.2 s |
| 盲复核耗时（一批 4 个 KP、12 道题）p50 / p90 | 6.5 s / 8.2 s |
| 通过的题 / 出的题 | 59 / 60（弃用率 1.7%） |
| 物理调用（按 `ai_calls` 实计） | 25 次 → 0.42 次/道 |
| 物理调用（每个 KP 各摊一次复核的保守算法） | 40 次 → 0.68 次/道 |

唯一弃用的题原因：`source_quote_not_verbatim`（模型给的原文引句不在教材页里）。

**补一批 3 道 ≈30.1 秒**（生成 28.5 + 复核摊 1.6）。没达到 ≤25 秒，原因是期中前按 d2 裁决改用「Codex 出、Claude 复核」，
gpt-6.1-sol 一次出 3 道要 17–72 秒；d4 spike 里 Claude 出题是 10–16 秒。期中后改回 Claude 出题能否回到 ≤25 秒，这次没测。

### 60 道选择题盲复核

6 批 × 10 道，一致 55 道（91.7%）；单批耗时 p50 9.3 s、p90 9.6 s。

复核分歧样例（5 条，均带复核给的理由）：

| 题 | 答案键 | 复核答案 | 复核缺陷 |
|---|---|---|---|
| 择医需求估计的偏误方向 | A | A | `ambiguous`：择医方向说得通，但题干没点明测量的是哪条曲线 |
| Grossman 模型 allostatic load | C | C | `other`：C 的标注写 `r + δ`，原文用 `r + γ`；意思对、记号错 |
| McGrath PCI 研究的具体数字 | D | D | `not_supported_by_source`：结论方向对，但「75→200」「4%→3%」这类具体数字在没有原文时无法核实 |
| 五家医院 HHI 的门槛归属 | C | **A** | `ambiguous`：0.225 在 1992 门槛下是「高度集中」，在 2010 门槛下是「中度集中」，题干没说是哪一版 |
| 保险合约的「价格」 | B | B | `ambiguous`：`r − pq` 与 `r/q` 两种「价格」读法都成立，题干应定义 |

4 条是「同意答案键但标了缺陷」，1 条（第 4 行）是真分歧：复核按旧门槛选了 A。这类分歧只产出复核结论，不写正式库。

### 植入错键题（5 道植入 + 1 道干净对照）

| 植入类型 | 道数 | 被拦下 |
|---|---|---|
| `no_correct`（没有正确选项） | 1 | 1（复核答 NONE） |
| `multiple_correct`（不止一个对） | 2 | 2（复核答对了键，但标了 `multiple_correct`） |
| `wrong_key`（答案键写错） | 2 | 2（1 条复核答 B、1 条标 `not_supported_by_source`） |
| 干净对照 | 1 | 0 误报 |

第一轮跑出 4/5：其中一条「六家平分」的题标注为错键，但键 A（非集中）其实是对的（HHI=1/6≈0.167），
是标注错了。改标注后重跑 5/5。两次结果都留着（`runs/planted.v1.json` 与 `runs/planted.json`）。

### 他本人的 5 条中文作答（#71/93/95/99/139）

18 条引文：原始形态 18/18 通过（误拒率 0/18），按「引整个分句」补齐后仍 18/18；5 条作答每条都有可核验证据。
覆盖到的都是 d6 列过的类型：句中片段、带标号的短答（`a 逆向选择`）、中英混写（`因为uncertainty引出`）、
全角数字、U+2212 减号、`负十一分之三` 这类中文分数。

### 构答题（KP `11-11-1`，moral hazard）

| 项 | 值 |
|---|---|
| 一次出 2 道（含参考答案 + 冻结 rubric）耗时 | 18.2 s |
| rubric 异厂复核 | CONFIRM（1 次物理调用，11.6 s） |
| rubric | 4 条、2/2/3/3 分、每条带原文依据，指纹 `2ba70bbd…` |
| 批参考答案（fail closed） | 未写入：3 条证据 `evidence_CROSSES_TOKEN_BOUNDARY` |
| 批半截答案 | 写入 1.0 分，异厂复核 CONFIRM（2 次物理调用） |
| 为一个已通过 KP 补 2 道构答题（出题 + rubric 复核） | **2 次物理调用、29.8 s** |

参考答案那次批改的 3 条引文被逐字校验判为「跨词元边界」，按设计不写任何结果。事后查明这是**校验器的 bug**：
引文以句末标点（`.`/`。`）结尾、而这个标点后面紧跟空白/引号时，边界判定只看「词元首尾」，把这种位置误判成词元内部。
已修（见「复核整改」），并用 5 个探针样例锁住；那一轮的 live 数字没有重跑，配额留给验收。

## 复核整改（Architect 关键异源复核退回的 4 项，2026-10-29）

| # | 问题 | 改法 | 锁住它的用例 |
|---|---|---|---|
| 1 | 引文以标点结尾、后面紧跟空白/引号时误判 `CROSSES_TOKEN_BOUNDARY` | 边界改成「不在任何词元内部的位置」；顺带修掉「引到原答末尾时区间带上尾部空白」 | `quoteEndingAtPunctuationFollowedBySpaceIsAccepted`（5 个探针）、`trailingWhitespaceIsNotIncludedInTheSpan` |
| 2 | 证据被拒就直接 fail closed，缺「带原因修复一次」 | 证据/算术检查失败时把原因发回原批改模型修一次（计入 `ai_calls` 与物理调用数），仍失败才不写 | `rejectedEvidenceIsRepairedOnceAndThenWritten`、`forgedEvidenceIsSentBackOnceAndThenFailsClosed`、`repairCannotChangeFrozenRubric` |
| 3 | 没校验 `served_by` / `fallback`，「异厂复核」可能同厂 | 关了兜底却收到 `fallback=true`，或 `served_by` 与请求不同家 → `served_by_mismatch` | `servedByFromAnotherVendorIsRejected`、`fallbackDespiteFallbackOffIsRejected` |
| 4 | 失败调用在 `ai_calls` 里记 `physical_calls = 0`，少算每日上限 | 非 200 时解析错误体里的 `x_coach` 取 `physical_calls`/`served_by`；传输异常保守按 1 次计 | `httpErrorKeepsBridgePhysicalCalls` |

探针（对着打好的 jar 跑，不是只在用例里）：

| 原答 | 引文 | 修前 | 修后 |
|---|---|---|---|
| `Adverse selection happens before the contract. Adverse selection is not moral hazard.` | `Adverse selection happens before the contract.` | 拒 | **通过** `[0,46)` |
| `The elasticity is 0.5. So demand is inelastic.` | `The elasticity is 0.5.` | 拒 | **通过** `[0,22)` |
| `需求弹性小。 所以降价没用` | `需求弹性小。` | 拒 | **通过** `[0,6)` |
| `第一，需求弹性小，⏎所以降价没用` | `第一，需求弹性小，` | 拒 | **通过** `[0,9)` |
| `他说“需求弹性小。”所以降价没用` | `需求弹性小。` | 拒 | **通过** `[3,9)` |
| `0.25` / `-0.25` / `逆向选择` / `重量是1㎏` | `25` / `0.25` / `选择` / `1k` | 拒 | **仍然拒** |

## 已知局限（照 ADR-0003 决定 9，不假装解决）

- 否定句、自我更正这类语义伪证任何长度规则都挡不住，只能靠异厂复核；本轮的 5 条选择题分歧全是语义层面的。
- 逐字校验的门槛会拒掉「比 8 个有效字符短、又不是整个分句」的引文（例如只引 `20%`、只引 `道德风险`）。这是设计要拦的类型，不是 bug。
- 20 道简答与人工评分的对照不在 M0；这里只有 AI 批改与异厂复核的一致率。

## 测试

`./gradlew :server:ai:test :shared:domain:jvmTest`：47 个用例，全部本地、不花钱。
覆盖 SSE 终态与截断、`X-Coach-*` 头部、served_by/fallback 异厂校验、错误体里的物理调用数、
盲复核 prompt 不含答案键、rubric 冻结指纹、伪造证据的「修复一次」与断流/REVISE 的 fail-closed、
中文与英文与全角与星平面与 NFKC 展开的 UTF-16 区间、中文数字与分数比对。

## 跑法与复跑

```sh
tools/corpus/prepare-gradegen.py check          # 语料装配齐不齐（教材页、60 道选择题、他的作答）
./gradlew :server:ai:test :shared:domain:jvmTest
./gradlew liveTest                              # 需要桥副本 8791 在跑：1 个 KP + 植入题 + 真实作答
```

桥副本 8791 起停：2026-10-29 20:42:13 UTC 起、21:40:45 UTC 停（`screen -S coach-bridge-8791`，按 PID 4811 停）；同期 DeepTutor 的 8790 未动（PID 70368 全程在）。
每次 Claude 请求前只读检查 DeepTutor 的 `bridge.log`，命中活动就中止整批、等下一个 10 分钟静默窗口——
本轮因此在 21:24–21:33 的窗口里跑完复核与批改；Codex 生成侧不占 Claude 订阅窗口（另走 ChatGPT 订阅），生成那 20 次是在他在线时跑的，只占本机 CPU。
