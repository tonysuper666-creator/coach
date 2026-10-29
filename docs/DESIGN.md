Coach 最终设计 v1（2026-10-29，负责人 Mika 整合裁决）

本文是团队据以开工的唯一设计。底稿是 `docs/design/round1-opus-full.md`（两份评审都建议以它为底），按节替换为评审与核查员的裁决。每条决定后的 `〔来源〕` 指向出处：`C#n` = `committee-result.json` 共识第 n 条，`d1–d8` = 分歧裁决（见 `docs/adr/`），`O/S/A §n` = Opus/Sonnet/Astra 设计第 n 节，`评O/评S/评A` = 三份评审，`评R3` = 设计第二意见 `docs/design/round3-architect-review.md`（TON-1036，F = 致命、I = 重要、m = 次要；按 Mika 2026-10-29 的裁决落地），`漏#n` = 立项卡列的「三份都漏掉」第 n 条。

标注：**决定** = 照此实现；**M0 验证** = 由 M0 spike 给出数字后定参；**开放** = 现在答不了，写明谁来答、什么时候答。

路径缩写：`SC/` = StudyCoach 源码（`gh repo clone tonysuper666-creator/studycoach`，只读）；`DT/` = `~/DeepTutor/.venv/lib/python3.12/site-packages/deeptutor/`；`~DT/` = `~/DeepTutor/`。

## 0. 一页纸

| 押注 | 决定 | 什么结果算押错 |
|---|---|---|
| 每次点按后的等待 | 服务端驱动的结构化状态机，不用 tool-calling；题池后台预生成并盲复核；开讲预生成，追问实时流 〔C#1、C#12、d3、d4〕 | 追问 n≥20 首字 p50 >5 秒；补题等待 >10 秒 |
| 公式与流式 Markdown | 服务端 MathJax 出 SVG + 客户端自绘 `MathSvgPainter`；不用 coil-svg、不用 KCEF 〔d1〕 | S1：课程公式错式/丢字；钉底流式已封口块位移 >0；Fold8 掉帧 >5% |
| 掌握度可信又不卡冲刺 | 两层：「已通过」= 最近 3 次有效首答全对并锁存；「稳固」= ≥2 条无提示、非 assisted 的有效首答（其中 ≥1 条构答；M1 实际 = ≥2 条构答）+ 跨 ≥2 个学习日 〔C#6、C#7、d4，评R3 I1、I2〕 | M1 七天里「明明会了却不让过」≥3 次 |

已拍板不再讨论：KMP + Compose Multiplatform（安卓原生 + macOS，之后 Windows）；服务端 Ktor（JVM）放这台 Mac、可迁移；AI 经订阅 CLI，走 DeepTutor 桥的**副本** 8791（8790 一个字不动）；一本事件账本；每个对话挂学习对象。

节奏：M1 不承诺 11-02 期中前可用，期中照用 DeepTutor 〔C#20〕。

## 1. 产品与学习闭环

### 1.1 用户旅程（决定）〔O §1.1，评O §6.1〕

- **每天**：打开 → 今天页主按钮「继续：<知识点>」（第 1 次点按）→ 导师立即开讲（预生成命中则零等待，未命中实时流）→「下一步」出题（题池已备好）→ 判分 → 已通过则提示下一个。打开到开始学习 ≤3 次点按。
- **学了多少**：今天页顶部一行「按期中分值：已通过覆盖 x / 100 分，稳固 y 分」。分值来自 `docs/background/期中分值覆盖表.md` 的 KP→分值映射（迁移时导入为课程的 `examWeights`，他可改）；按 KP 个数的比例只在路线页显示。
- **考前冲刺**（距考试 ≤7 天）：倒计时；复习间隔压缩（§2.4）；Essay 类 KP 讲完后「下一步」默认建议「考试写法」。
- **手机碎片**（外屏）：「复习 3 题」一手可做；选择题选项 ≥56dp；离线时选择题也即时反馈（§1.4）。
- **折叠/展开**：同一张题卡原样放大；草稿、已选选项、流式输出、滚动锚点不丢（§8）。
- **Mac 离线/休眠**：今天/路线/记录/历史对话/已缓存原文照常可看；可做离线练习包；讲解与批改显示「Mac 未连接（最后同步 hh:mm）」，绝不显示占位内容 〔C#15〕。

### 1.2 屏幕（决定）〔O §1.2〕

六屏：今天、路线、学习（KP 线程）、复习、记录、资料。主动作、空状态、错误态照 `O §1.2` 表格。补充：
- 「学习」顶部显示「本次参考：教材 p.8–12」；导师消息头显示**实际出答的模型名**（含兜底）〔C#14〕。
- 「记录」里每个掌握变化可下钻到原题、原答、rubric、逐字证据高亮、批改与复核模型；锁存撤销、孤儿作答也在这里显示原因（§2.2、§4.3）。
- 「路线」里还没有已复核构答题的 KP 标「此 KP 暂无构答题」：这时稳固层到不了，如实显示，不降低标准 〔评R3 I1〕。

### 1.3 对话模型（决定）〔O §1.3，C#8〕

| 种类 | 挂在 | 数量 | 能写的学习事实 |
|---|---|---|---|
| 知识点线程 | 一个 KP | 每 KP 恰好一条，跨天延续 | 讲解、追问、出题、判分、证据 |
| 复习会话 | 会话开始时的 KP 列表快照 | 每次一条 | 复习结果（只改日程）；合格作答可计入稳固 |
| 随口问 | 课程 + 当前 KP（只读） | 每逻辑日一条 | 不写任何掌握证据；可「存为笔记」「移到 KP 线程」（M1 只这两个动作）〔评O §6.2〕 |

用户永远不选笔记本/题库/阅读集。多位导师 = 服务器按任务分配的模型角色，透明但不可选；「换个老师讲」M1 只有 Codex（Grok 待桥副本 live 实测后再上，d2）。

**有待答题时的追问**（决定，推翻 O 的「每次导师回复算一次提示」）〔d4 建议 1、评S D6〕：
- 只有用户点「要提示」才记 `hint_revealed`；带提示的作答**不进**已通过窗口（不按半分，不算错）。
- 待答时追问不记提示，但该作答由投影判为 assisted（§3.2，不写进作答载荷）：不影响「已通过」，**不算稳固证据**。输入框上方一行字：「现在问不影响已通过，但这题不算稳固证据」。导师在待答状态下的系统提示要求不直接给出答案；选择题服务端另做确定性泄题检测（回复含正确选项字母+文本）→ 命中则记 `hint_revealed cause=LEAK`。
- 理由：核查员要求取消自动记提示（否则 3 连对几乎过不了，重现 10-28）；但完全不记会让「问完再答」成为稳固证据，稳固就失去意义。`assisted` 只挡稳固，两头都保住。

### 1.4 离线即时反馈（决定）〔漏#2，评S §5-1，评O §5-3〕

离线练习包（`GET /offline-pack?size=10`）随题下发**选择题**答案键，存在客户端单独的表 `offline_keys`，界面层在作答提交前读不到。流程：作答先写进发件箱并锁定（不可再改）→ 本地判分立刻显示对错 → 联网后服务端精确重判，以服务端结果为准。构答题离线只存答，联网后批改。

离线包里的题在有效期内为该设备预留：不再发给其他设备，也不进在线题池；过期即释放回题池。这样两台设备不会拿到同一道题，后答的那台也就不会出现「本地显示对、却算不上有效首答」〔评R3 m3〕。

这推翻共识 C#10 的一半（「答案键不进下发给客户端的 DTO」）：题目 DTO 仍然不含答案；答案键只通过离线包这一个端点、只含选择题。理由：单用户应用要防的是「先看到答案」，提交前锁定就保证了首答纯度；Mac 小时在线率 11–38%，手机碎片时间多半离线。

## 2. 学习规则

### 2.1 作答资格（决定，纯函数，`shared/domain`）〔O §2.1，d4，C#8〕

`purpose` 由用户显式发起的活动决定：「练一题」= `PRACTICE`；「闭卷检查」= `CLOSED_BOOK`；复习里首次见到的题 = `UNSEEN_VARIANT`，见过的 = `RECALL`；模考 = `TIMED/FULL_MOCK`（M2）。

一次作答是**有效首答**，当且仅当：未作废；判分状态 ∈ {EXACT, VERIFIED}；题目 `keyState=VERIFIED`（迁移来的 108 题也一样，未复核不计任何一层）；同一题在该 KP 上第一次作答；题目指纹 14 天内未答过（按 `occurredAt` 算，`occurredAt` 先截断到 [该 attempt 的 posedAt, recordedAt] 之间，防设备时钟漂移）〔评R3 m7〕；未点「要提示」；origin ≠ LEGACY。

**迁移来的作答（origin=LEGACY）永不进任何窗口**（已通过、稳固都不进）：它们只用于 `legacy_passed` 锁存和记录页展示；108 题在 M1 复核通过后，旧作答也不回溯计入 〔评R3 I4〕。

### 2.2 两层掌握（决定）〔d4 建议 1，C#6、C#7〕

| | 已通过（冲刺层） | 稳固（严格层） |
|---|---|---|
| 判据 | **最近 3 次有效首答全对**（选择题精确对；构答得分率 ≥0.8），3 道题指纹互不相同 | 合格证据 ≥2 条，其中 ≥1 条构答（简答/论述/计算），分布在 ≥2 个逻辑日，平均得分率 ≥0.85。**M1 下稳固 = ≥2 条有效构答**（单选只在 TIMED/FULL_MOCK 合格，模考在 M2）〔评R3 I1〕 |
| 合格证据（稳固） | — | 必须是**有效首答**（§2.1）且 `hints = 0`、非 assisted（删去沿用 SC 的 `hints <3`，免得点过提示的作答进得了严格层、进不了冲刺层）〔评R3 I2〕；另沿用 SC `mastery-evidence-policy.ts` 去掉阶段后的条件：purpose ∈ {CLOSED_BOOK, UNSEEN_VARIANT, TIMED, FULL_MOCK}；判分 ∈ {EXACT, VERIFIED}；单选题只在 TIMED/FULL_MOCK 合格；离线作答不可能是 TIMED；origin=LEGACY 永不合格 |
| 锁存 | 首次达到即锁存；之后答错不撤销，只标「需回炉」，明天进复习；证据被作废时按下文撤销 | 最近一次合格情境失败 → 显示「曾稳固」，直到新的合格通过 |
| 窗口排序 | 按服务端 `seq`（答案提交入账顺序），不按设备时钟 〔C#3〕 | 学习日按 `occurredAt` 归日 |
| 展示 | DT 5 次加权分只作展示 | 进度「2/2 条 · 构答 1 · 2 天 · 均 0.88」 |

**有效性变化与锁存**（决定）〔评R3 I5、m5〕：
- 有效性的变化本身就是事件：复核完成（`grade_reviewed`）、`attempt_voided`、题目 keyState 变化（`question_key_changed`，§3.2）。投影仍是纯 fold，按 seq 逐个事件推进。
- 锁存在每个事件处，按「截至该事件已知的有效性」判定。构答复核晚到时，锁存在复核事件那一刻成立，不往回改写更早时刻的结论。
- 锁存只抵抗「之后答错」，不抵抗「证据本身被作废」。作废或答案键被判错（keyState → REJECTED）后，若支撑这次锁存的有效首答不足 3 条，就在作废事件处撤销「已通过」，记录页写明原因（哪道题、为什么作废）；之后照常按窗口重新达标。`legacy_passed` 不依赖作答，不受此规则影响。

不变量：`稳固 ⇒ 已通过`（定义 `passed := 窗口达标 ∨ solid ∨ 手动标记 ∨ legacyPassed`）；手动标记与迁移永不产生稳固。

**与复习的关系**（修 评A 指出的 O 的矛盾）：复习队列只含已通过的 KP（首次已通过才开始排期），所以「复习合格作答可计入稳固」与「复习结果不改已通过」不冲突：复习 FAIL 只标需回炉、缩短间隔，不撤销已通过。

迁移不掉级：已核实「最近 3 次全对」对 DT 已通过的点必然成立（d4 穷举）；DT 的 5 个已通过 KP 另以 `legacy_passed` 事实锁存，不依赖新公式重算〔评A §2〕。

### 2.3 前置与阶段（决定）〔C#8，O §2.3〕

前置依赖软锁（`next()` 跳过，路线上可手动打开并提示「建议先学 X」）；现 `prerequisite_ids` 全空，M1 由模型提议、他确认（M1 一张小卡）。不做全局阶段状态机；时间本身不推进任何状态。模考首做/修复双轨 M2。

### 2.4 复习（决定）〔O §2.4，C#9，评O D6〕

队列项 = KP，题只是载体（取题顺序：本 KP 答错未再对 → 未见题库题 → 已复核变式 → 最久未见）。**已复核变式** = 为同一 KP 新生成、指纹与该 KP 已有题都不同、经异厂复核后 `keyState=VERIFIED` 的题：选择题 = 盲复核（不看键、自己作答）与键一致；构答题 = 参考答案与冻结 rubric 经异厂复核同意 〔评R3 I1〕。调度用 SC 的 ease 模型（`SC/packages/projections/src/review-queue.ts`）：首次已通过间隔 1 天、ease 2.3；稳固种子 3 天；每 KP 每逻辑日只有第一次复习作答参与调度；PASS ≥0.8 / NEEDS_WORK 0.5–0.8 / FAIL <0.5 的更新照 O §2.4。考前压缩：间隔上限 = max(1, ⌊(距考天数−1)/2⌋)。「继续」不被到期复习抢占；到期复习是今天页第二张卡。先看队列，点「开始」才出题。迁移来的 5 条复习状态按 `next_review_at` 接管；KP 不在 `legacy_passed` 里的那几条不进复习队列，列进迁移对账报告（§11）〔评R3 m8〕。种子与阈值 M1 两周后按体感调（只调参数）。

### 2.5 日记（决定）〔O §2.5〕

逻辑日以课程时区 04:00 为界。`JournalDay = 统计 + AI 摘要（记 fromSeq，有新事件标「已过时」）+ 人工区（rev 乐观锁）+ DeepTutor 旧日志区`。

### 2.6 教学模式（决定）〔O §2.6，评O D9〕

模式表照搬 SC 九种，M1 只实现：当前讲、直觉讲、换例子、正式讲、考试写法。不弹窗、不擅自切：同一 KP 答错 2 次后，「下一步」按钮本身变成「换个例子再讲」，不多一次点按。

## 3. 领域模型与事件账本

### 3.1 一等对象（决定）〔O §3.1〕

照 `O §3.1` 的 Kotlin 定义（`Course / Module / KnowledgePoint / SourceAnchor / Question / LearningObjectRef / Thread / AnswerKey / Criterion`），修改：
- `Course` 加 `examWeights: Map<KpId, Int>`（期中分值，§1.1）。
- `Question.keyState`：VERIFIED / UNREVIEWED / REJECTED；`fingerprint = sha256(规范化题干 + 选项)`。
- 分数一律整数（`score`、`max` 为 Int），得分率在投影里算 〔评S §6：A 的整数分〕。
- `AnswerKey` 只存在于 `server/core`，不进 `shared/protocol`（离线包的选择题键用单独 DTO `OfflineKey`，只在 `/offline-pack` 下发）。

### 3.2 事件（决定）〔O §3.2，C#2、C#3、C#4，评O §6.1〕

信封：
```kotlin
Event(seq: Long /*服务端分配，全库单调递增；按课程读时有空洞是正常的*/, id: Ulid, courseId, type, schema: Int,
      recordedAt /*服务端时间*/, occurredAt /*设备时间，只用于归日与展示*/, deviceId, actor /*LEARNER TUTOR SYSTEM MIGRATION*/,
      commandId?, effectIndex: Int, causationId?, threadId?, payload)
```
约束：`UNIQUE(device_id, command_id, effect_index)`，同 `(device_id, command_id)` 载荷哈希不同 → `REJECTED`；业务唯一键 `UNIQUE(course_id, idem_key)`（如 `attempt:{id}:answer` 跨设备也只能一次）〔评A §6〕。载荷哈希在发件箱落库时对规范化字节算一次，之后原样上传这份字节与哈希；服务端比对 `command_log` 里存下的哈希，不重新序列化。这样 App 升级改了序列化，旧发件箱重放也不会被误判 `PAYLOAD_MISMATCH` 〔评R3 m2〕。`seq` 全库单调（修 O 的「课程内连续」与 DDL 矛盾，评A §2）。

事件类型以 `O §3.2` 表为准，增补：

| type | 关键字段 | 说明 |
|---|---|---|
| `legacy_passed` | kpId, sourceRef | 迁移来的已通过，直接锁存「已通过」，永不计稳固 |
| `exam_weights_set` | weights | 期中分值映射 |
| `explanation_pregenerated` | kpId, msgId, packHash, model | 预生成开讲入库（§5.2），不是学习事实，不计入掌握 |
| `hint_revealed.cause` | BUTTON / LEAK | 去掉 O 的 TUTOR_REPLY |
| `server_restored` | epoch, fromBackup, lastSeqBefore | 回档（§4.3） |
| `question_key_changed` | questionId, keyState, reason | 题目复核通过 / 答案键被判错；有效性变化入账，锁存按事件顺序判定（§2.2）〔评R3 I5〕 |
| `answer_orphaned` | attemptId, posedSnapshot, rawAnswer | 回档后题目已不在库：保留原文、不计分、不进任何窗口，记录页可见（§4.3）〔评R3 F1〕 |

删去原增补的 `answer_submitted.assisted` 〔评R3 I3〕：assisted 由投影计算——该 attempt 的 `question_posed` 之后、`answer_submitted` 之前（按 seq），若存在 `pendingAttemptId` 等于它的 `followup_asked`，这次作答就是 assisted。在 Mac 上追问、在手机上作答也能判对。

证据**不是事件**，是投影：由 posed + submitted + graded + reviewed 按 `question_posed.policyVersion` 指定的策略算出；「首次见题 / 重复 / attempt 序号 / assisted」由投影按 seq 重算，不写死在事件里〔O §3.2，评S D2〕。

对话正文不进账本，存 `messages` 表；但备份的 `events.jsonl` 同时导出 messages，重建不丢对话 〔评S §6〕。

### 3.3 投影（决定）〔O §3.3〕

全部 `fold(events) → State`，不读「现在」（只有「今天到期」过滤读当前逻辑日）。Position / Mastery / Review / Journal / ExamCoverage（新增：按 examWeights 汇总已通过与稳固的分值）。投影有 `PROJECTION_VERSION`，变了就全量重放；冷重放耗时在 Android 上实测（只报告；超过 800ms 才引入增量快照）〔评S §6 spike 3〕。

### 3.4 版本化（决定）〔O §3.4，评O D8〕

每个事件类型独立 `schema`，`upcast(raw)` 链，不改写历史。客户端遇到未知事件类型：保留原文；若该类型影响某个视图，该视图改用服务端快照并提示更新。

## 4. 服务端与存储

### 4.1 模块（决定）〔O §4.1，d5〕

```
shared/domain     零 IO：对象、事件、策略、投影
shared/protocol   DTO、命令、WS 帧、版本号
shared/client     SyncEngine、发件箱、本地库（SQLDelight）
shared/ui         主题、组件、屏幕、渲染（MathSvgPainter、流式 Markdown）
androidApp/  desktopApp/            （AGP 9 要求安卓应用独立模块）
server/{app,core,store,content,ai}
tools/{bridge(py 副本), mathsvc(node), migrate-deeptutor, dev}
acceptance/                          验收脚本、语料、录屏（只有验收者改）
```
单写者：所有写命令经 `server/core` 里一个协程 actor 串行执行；事件 + 投影缓存 + 命令结果同一 SQLite 事务提交（WAL）〔C#5〕。

### 4.2 表（决定）〔O §4.2〕

以 `O §4.2` DDL 为底，修改：
- `events`：加 `command_id`、`effect_index`、`payload_sha256`，约束见 §3.2。
- `command_log(device_id, command_id, payload_sha256, result, at)`。
- 新 `jobs(job_id, kind, subject, fence, state /*queued running completed failed cancelled，终态互斥*/, attempts, last_error, created_at, updated_at)`：后台出题、批改、复核、预生成都是 job；提交结果时校验 `fence` 与 `questionVersion/answerHash`，过期 fence 的结果丢弃 〔C#5，评A §6〕。job 每被领取一次 fence +1 〔评R3 I6〕。
- `turn_leases` 加 `fence INTEGER`，每次续租不变、每次新授予 +1。
- fence 一律按 `(epoch, fence)` 比较，不单比 fence：回档后库里的 fence 会回到旧值，单比数字会把回档前发出的租约或任务当成有效 〔评R3 I6〕。
- `messages.status`：streaming / complete / truncated / cancelled / failed（与桥 `x_coach.status` 对应，§5.4）。
- `math_svg(sha256, tex, display, svg, w_em, h_em, d_em)`：尺寸用 em（宽、基线以上 h、基线以下 d）〔d1 建议 2〕。
- `ai_calls` 加 `request_type, effort, pack_chars, queue_ms, physical_calls, cache_read, cache_create, served_by, status`（只记长度与耗时，不记内容）〔d3 建议 6〕。
- `ledger_meta(epoch TEXT)`：服务端纪元（§4.3）。
- 双 FTS：`fts_en`（porter unicode61）+ `fts_zh`（trigram）；中文 ≥3 字走 trigram MATCH，1–2 字退 `LIKE '%x%'` 并按课程限定 〔d8〕。

### 4.3 备份与回档（决定）〔O §4.3，漏#7〕

每晚（醒着时 03:30；错过则在下次启动或唤醒后、距上次备份已超 24 小时时补做）`VACUUM INTO ~/Coach/backups/coach-YYYYMMDD.sqlite` + 同日 blobs 目录的硬链接快照 + `events.jsonl`（含 messages），保留 14 日 + 8 周；每次结构迁移与数据迁移前强制备份 〔评A §2：备份遗漏 blob〕。blobs 内容寻址（按 sha256 命名）、只写一次、从不原地修改——这是硬链接快照成立的前提 〔O §4.2，评R3 m4〕。

回档 〔评R3 F1〕：
- 从备份恢复时生成新 `epoch` 并写 `server_restored`（`lastSeqBefore` = 所用备份里的最大 seq）。
- 恢复时把所有非终态 job（queued/running）置为 cancelled 再按原 subject 重新排队，清空 `turn_leases`；之后的 fence 都在新 epoch 下比较（§4.2）〔评R3 I6〕。
- 服务端在每条命令回执和 `hello` 里带 `backedUpThroughSeq`（最近一次成功备份覆盖到的 seq）。客户端发件箱保留两类命令：未确认的；已确认但 seq > `backedUpThroughSeq` 的——后者要等被备份覆盖才删。只靠「未确认的重放」不够：最近一次备份之后、回档之前已入账的作答，回档后哪里都没有。
- 客户端 `hello` 带上自己记住的 epoch；不一致 → 丢弃本地账本副本、拉快照重建，并重放所有未确认命令，以及 seq 大于新库 `lastSeqBefore` 的自己的已确认命令；服务端照常按 commandId 去重。
- `submit_answer` 命令自带出题快照（attemptId、questionId、fingerprint、kpIds、purpose、policyVersion）。服务端遇到未知 attempt（例如备份早于离线包下载），按快照补写 `question_posed`；题目本身也不在库里，就写 `answer_orphaned`：保留原文、不计分、不进任何窗口，记录页可见（沿用 O §4.4「离线作答时题目已作废：作答保留、不计」）。
- 边界：每台设备只重放自己的命令；某台设备在回档后再也不上线，它的那部分作答仍会缺。桌面端本地副本回灌其他设备事件不进 M0（§14 #8）。

### 4.4 多设备（决定）〔O §4.4〕

照 `O §4.4`：回合租约（第二台设备旁观、消息排队、可打断）；同一 attempt 两处作答先到者生效（409 + 已判结果）；日记 rev 乐观锁；KP 只退役不删除。

### 4.5 三个常驻进程的托管（决定）〔漏#3〕

Ktor 服务是唯一的监督者：它以子进程启动桥副本（Coach 自己的 venv）和 mathsvc（Node），各自健康检查（`/health`，10 秒一次）、崩溃后指数退避重启（1 s → 60 s 封顶）、日志写 `~/Coach/logs/{server,bridge,mathsvc}.log`（按天轮转，保留 14 天）。服务端退出时杀掉整个子进程组。
- M0：手动运行 `tools/dev/run-all.sh`（由账本卡 TON-1042 提供，它第一个需要三个进程一起跑）〔评R3 m9〕，不装任何 LaunchAgent。
- M1：装一个用户级 LaunchAgent `local.coach.server`（KeepAlive），只管 Ktor；卸载 = `launchctl bootout gui/$UID/local.coach.server` 并删 plist。
- 不做防休眠（`caffeinate`），离线体验靠 §1.4；唤醒后的收尾见 §4.7。

### 4.6 可迁移（决定）

所有运行数据在 `~/Coach/`（db、blobs、backups、logs、bridge 的工作目录）；代码与 venv/node_modules 在仓库构建产物里。搬到常驻机器 = 拷 `~/Coach/` + 在新机器上登录各家 CLI + 改客户端服务器地址。迁移步骤写进 M3 文档，不提前实现。

### 4.7 休眠与唤醒（决定）〔评R3 I7〕

Mac 小时在线率只有 11–38%，休眠是常态，不是异常。
- **检测**：服务端每 5 秒采样一次墙钟与单调钟（`System.nanoTime`）。两者之差比上次突然变大（≥30 秒）即判为唤醒；采样协程两次运行的间隔远超周期（≥30 秒，覆盖进程被挂起、`kill -STOP` 模拟）同样按唤醒处理。JVM 单调钟在 macOS 休眠期间走不走，账本卡实测后写进交付，不预设。
- **唤醒后**：进行中的消息按 `truncated`（reason=`suspended`）收尾，客户端照常给「重试」；`running` 的 job fence +1 后重新排队；检查 Tailscale IP 上的监听，失效则交给 §6.1 的重绑协程；距上次备份已超 24 小时就补做备份（§4.3）。
- **时钟规则**：持久化的期限用墙钟——休眠期间到期是期望行为；进程内的超时用单调钟——休眠期间不走的部分由唤醒检测兜住。

| 期限 / 超时 | 钟 |
|---|---|
| 回合租约到期、job 超时、复核 24 小时内重试（§5.3）、配对码 5 分钟、离线包有效期、每晚备份时刻 | 墙钟（持久化） |
| 首字截止 25 秒与 8 秒「还在想」、流总时长 180 秒、30 秒无 delta、JSON 90/180 秒（§5.5）、查原文 90 秒（§5.1）、健康检查 10 秒、登录检查 5 分钟、退避重启（§4.5）、唤醒采样 5 秒 | 单调钟（进程内） |

新增的超时必须在这张表里登记用哪种钟。
- **测试**：账本卡对服务端进程组 `kill -STOP` / `kill -CONT`，验证手机能重连、进行中的任务能收尾。**期中（2026-11-02 15:00）前不重启 Tailscale**（他手机上的 DeepTutor 走这条网络），「重启 Tailscale 后重绑」推到期中后补测。不用 `pmset sleepnow`。

## 5. AI runtime

### 5.1 编排（决定）〔C#1，O §5.1〕

服务端结构化状态机：`IDLE → PREPARE(确定性备课，≤6 页、≤40K 字) → TEACH(流) → AWAIT → POSE(题池取，零等待) → AWAIT_ANSWER → GRADE → REVIEW(后台异厂复核) → FEEDBACK`。只有「流式纯文本」和「JSON 非流式」两类调用；掌握度更新由纯规则代码完成。

导师自己翻原文：**李政 2026-10-29 拍板 = 导师自动判断，有必要才翻**（不加按钮，也不每次都翻）〔评O D10〕。默认仍走备课包，只在下面两级触发时才「翻原文」，没触发的回答延迟与现在一致：

1. **补页（确定性，无模型）**：追问/问答发出前，服务端用问题在本课程库做本地检索；若命中的高分页不在当前备课包里，就按分数把这些页并进本轮上下文（总量仍守 ≤6 页、≤40K 字，挤掉低分页）。只多一次本地检索，不多一次调用。
2. **查原文（服务端编排的只读子任务）**：导师按备课包作答，若判断备课包不够，在回答末尾输出固定标记 `【需查原文:<要找什么>】`（服务端剥掉，不显示）。服务端收到后起一次只读沙箱查阅（白名单复用 `~DT/tools/sandboxed_agent.sh`，复制进 Coach 再审；只读课程库，排除 `answer_keys` 与「答案」目录），结果作为同一条消息的「补充」段流式追加，前面显示「导师去翻原文了」，可取消。补充段同样走 §5.3 锚点与逐字引文校验。

约束：每个问题最多触发一次查原文、单次 ≤90 秒、超时只保留已校验内容；不写掌握度、不出题、不批改；讲解开讲与批改不触发；§5.4 额度保护触发时（含期中前）只做第 1 级。每次查原文写 `ai_calls`（`request_type=source_lookup`），M1 报告触发率、平均耗时、补充段引用合格率。这不是 §13.1 排除的「自由代理」：是否查、查多久、查完怎么用都由服务端决定，导师只能提出请求。

### 5.2 任务表（决定）〔O §5.2，d3，d4〕

| 任务 | 流式 | 主 | 兜底 | 复核 | 上下文 | 计入 |
|---|---|---|---|---|---|---|
| 开讲 | 预生成 | Claude Sonnet 5.5 | Codex（非流式） | 否 | ≤40K 字 / ≤6 页 | 用户打开时写 `explanation_delivered` |
| 追问 / 问答 | 是 | Claude Sonnet 5.5 | Codex（非流式，显示真名，约 20–50 秒） | 否 | 备课包 + 最近 12 轮 | `followup_asked` |
| 出题（后台） | JSON | Claude：1 次出 3 道 | 合入下一批 | **Codex 盲复核**（不看键、自己作答，再比对） | ≤20K | 复核一致 → VERIFIED 入池 |
| 构答出题（后台，KP 已通过后） | JSON | Claude：1 次出 2 道（带参考答案与冻结 rubric） | 合入下一批 | **异厂复核**参考答案与 rubric | ≤20K | 复核同意 → VERIFIED 入池 〔评R3 I1〕 |
| 构答批改 | JSON | Claude | Codex | **异厂复核**（看完整原答 + 冻结 rubric + 证据） | ≤15K | 先写 `attempt_graded(AI_UNREVIEWED)`，复核 CONFIRM/REVISE 后才计入 |
| 选择题判分 | — | 无模型 | — | — | — | 立即 EXACT |
| 计算题终值 | — | 服务端数值比对（带容差，解析常见中文数字） | — | — | — | AI 只批过程 〔d6 建议 9〕 |
| 日记摘要 | JSON | Claude | Codex | 否 | ≤10K | `journal_summarized` |

**开讲预生成**〔d3 建议 1〕：前一个 KP 已通过时、或打开 App 时，为「继续」目标的下一个 KP 生成开讲并存库（真实模型输出，记模型名与生成时间，备课包哈希变了即失效）。「继续」从库里流式放出，零等待。命中率与未命中时的实时首字都如实报告，不用占位冒充。

**题池**〔d4 建议 2–4〕：当前 KP、后两个 KP、今日到期 KP 各常备 ≥3 道未见过的已复核选择题；未见题 <3 或刚答错一道时立即补一批（1 次 Claude 出 3 道 + 1 次 Codex 盲复核，实测约 21 秒，缓冲 ≥2 道即零等待）。当前 KP 补题算前台调用。其余 KP 按优先级逐步铺池。被弃的题并入下一批，不单独重出。预算是报告值：「按批平均每道已复核题物理调用数」（实测 0.8，目标 ≤1.5）。同步出题否决。

**构答题供给**〔评R3 I1〕：KP 一旦已通过，后台为它补 ≥2 道已复核构答题（带参考答案与冻结 rubric，rubric 经异厂复核同意后 VERIFIED 入池）；按需补，不预先给 49 个 KP 全备。调用预算（估算，TON-1041 实测每道题耗时与物理调用数后替换）：每个 KP 一批 = 1 次出 2 道 + 1 次异厂复核 ≈ 2 次物理调用、约 1 次/题；若 49 个 KP 全部通过约 100 次，按已通过的节奏摊开。期中前按 §5.4 走「Codex 出、Claude 复核」并计入每日上限。还没有已复核构答题的 KP 到不了稳固层，路线页如实标出（§1.2）。

### 5.3 确定性校验（决定）

- **逐字证据**〔d6，见 ADR-0003〕：词元 = 每个汉字 / 整个数字 `[+\-]?\d+([.,/:]\d+)*%?` / 连续拉丁字母；证据起止必须落在原答词元边界上；门槛 = 有效字符 ≥8，或恰为所在整个分句（≥2 有效字符），或就是整个答案；NFKC + 空白归一 + 各种减号统一成 `-`；字符区间由服务端在归一化文本里定位后映射回原答，存 UTF-16 `[start,end)`，模型只给引文不给偏移。失败带原因调一次修复，仍失败 = 批改不可用，不写掌握。
- rubric 冻结（条数、文本、分值、依据不得改）、分数算术一致、来源归属：照 SC `grade-validation.ts`、`source-validation.ts`。
- **流式讲解引用**：段末 `【S3】` 锚点 + 事后校验；未知锚点删除；带引号引文必须逐字出现在页文本；无有效锚点 → coverage=PARTIAL 〔O §5.3〕。
- **答案隔离**：讲解与追问的备课包永不含 `answer_keys` 与「答案」目录；sealed 不导入 〔C#10、C#17〕。
- 复核不可用：24 小时内后台重试 3 次，仍不行永久停在「未计入」〔C#11〕。

### 5.4 桥副本 8791（决定）〔d2，见 ADR-0002〕

Coach 仓库 `tools/bridge/` 放 `~DT/tools/subscription_bridge.py` 的副本，跑 8791，必改九项（路径迁出、`x_coach` 显式终态、`served_by`、`X-Coach-Fallback: off`、`X-Request-Id` 与按请求推理强度、计时从拿到信号量起算、并发协调、能力表 `true_stream`、故障注入回归）。Coach 只把 `status==complete` 记为讲解完成；收不到终态帧按截断处理。

**并发与额度保护**：副本 Claude 并发上限 2，后台任务最多占 1；读 `rate_limit_event`，utilization ≥0.8 或 status≠allowed 时暂停后台生成。**期中前保护**〔d2 裁决，评R3 I8〕：
- 至 2026-11-02 14:25，副本 Claude 并发上限 1。
- (a) **每个** live 请求发出前只读检查 `~DT/data/user/runtime/bridge.log` 最后一行时间（UTC），10 分钟内有活动就中止整批，不只在每批开始前看一次。
- (b) 已知局限：bridge.log 在请求结束时才写一行，只能挡住「他正在问模型」，挡不住「他在复习但没调模型」。真正要护的是两份桥共用的订阅额度窗口，靠 (c)；CPU 靠 `nice`（§13.2）。
- (c) 至 2026-11-02 15:00：Coach 的 Claude 物理调用每日硬上限 60 次（按 `ai_calls` 计，含复核与 `dev_live`）；`rate_limit_event` utilization ≥0.5 或 status≠allowed 时暂停 Coach 的**全部**调用（不只后台）；2026-11-01 18:00 至 11-02 15:00 不跑任何 Claude live。
- (d) 批量出题一律「Codex 出、Claude 复核」（满足异厂盲复核），复核计入 (c) 的上限；超出上限就推迟到期中后，不降为同厂复核。
- (e) 8791 只在测试时段手动起，测完按 PID 停；每张卡交付里报告起停时间。

Antigravity 默认关。

### 5.5 取消 / 超时 / 兜底（决定）〔d3 建议 3，C#14〕

首字截止 25 秒（8 秒显示「还在想」，不算首字）→ 主动断开、桥杀进程组 → Coach 显式调 Codex 非流式，消息头写真名。流总时长 180 秒；30 秒无新 delta 判卡住。JSON 任务：输出 ≤4K 字 90 秒，更大的拆批或给 180 秒。出字后失败 = TRUNCATED，保留文字、给「重试」，不自动兜底。取消 = 杀整个 CLI 进程组，2 秒内无残留。以上都是进程内超时，用单调钟（§4.7）。

### 5.6 订阅掉登录（决定）〔漏#8〕

桥副本 `/health` 返回每家 CLI 的登录状态（只读调用 CLI 自带的状态命令，不读凭证文件），Ktor 每 5 分钟问一次。某家掉登录：该家任务改由兜底模型做并显示真名；手机上横幅「Claude 在 Mac 上需要重新登录，现在由 GPT-6.1 Sol 代答」；Mac 桌面端同一横幅带「复制登录命令」按钮。两家都不可用：讲解/批改显示「导师离线」，复习与离线包照常。Coach 不依赖 Multica 的值守脚本。

### 5.7 额度可见（决定）〔漏#5〕

`ai_calls` 汇总进设置页「用量」：今天 / 本周的物理调用数、按任务与模型分、后台占比。只报告不设卡。开发期 Multica 团队的 live 测试同样写 `ai_calls`（`request_type=dev_live`），每张 M0 卡交付时附本卡的调用数。

## 6. 协议

照 `O §6` 的端点、命令、WS 帧与版本协商，修改：
- `hello` 增加 `epoch` 与 `backedUpThroughSeq`；命令回执也带 `backedUpThroughSeq`（§4.3）〔评R3 F1〕；`submit_answer` 自带出题快照（§4.3）；`msg.end` 增加 `status`（complete/truncated/failed/cancelled）与 `reason`，与桥 `x_coach` 一一对应。
- `msg.math` 尺寸单位改为 em：`{w,h,d}`。
- 断线续传：进行中的消息每秒落库，重连先 `msg.snapshot` 再接 delta，按 `(msg, i)` 去重。这条 Ktor ↔ 桥 ↔ WS 客户端链路由 M1 第一张卡实现并验收（§13）〔评R3 I10〕。
- 命令结果 `OK | DUPLICATE | REJECTED(PAYLOAD_MISMATCH | ...) | CONFLICT(409，附已判结果)`。
- 认证与传输见 §6.1。

### 6.1 传输与鉴权（决定）〔d7、C#16，见 ADR-0004〕

- 服务只监听 `127.0.0.1`（启动即绑）和本机 Tailscale IP（本机配置 `COACH_TAILSCALE_IP`，不入仓库；监督协程按退避反复重绑，直到 Tailscale 就绪；唤醒后也检查一次监听，失效即交给它重绑（§4.7）〔评R3 I7〕；绑不上不拖垮服务）；不绑 0.0.0.0。
- 明文 HTTP/WS 跑在 WireGuard 之内；Android `network_security_config`：base-config 禁明文，domain-config 只放行 Mac 的 Tailscale IP（构建时从本机配置注入；可再加本 tailnet 的 `*.ts.net` 子域以便迁机）；不照搬 DeepTutor 壳的全局 `usesCleartextTraffic=true`。
- 设备令牌：Mac 桌面端显示 6 位配对码（5 分钟有效）→ 手机输入 → 256 位随机令牌，服务端只存 sha256，可逐台吊销；令牌只放请求头，不进 URL；**本机回环请求也要带令牌**（为将来 `tailscale serve` 留路，那时所有请求都来自回环）。
- `tailscale serve` HTTPS 不在 M1 做（要他改 tailnet 设置）。`tailscale serve --tcp` 转发作为 S0 的一项实测，若可行可替代重绑协程（工程选择，无需他定）。

## 7. 客户端

### 7.1 工具链（决定）〔d5，见 ADR-0001〕

Gradle 9.5.0 / Kotlin 2.4.0 / CMP 1.12.1（material3 1.12.0-alpha03）；`libs.versions.toml` 显式钉 lifecycle 2.11.0、kotlinx-serialization 1.11.0、androidx.navigationevent 1.1.2；桌面端可 `--offline`。安卓：compileSdk 37、AGP ≥9.1.1（缓存有 9.3.2）、**minSdk 34**、targetSdk 35（只有他的两台新设备和 API 35 模拟器要装；API 34 起系统 SQLite ≥3.39，支持 UPSERT、窗口函数、RETURNING；minSdk 26 只有 3.18）〔评R3 m1〕；S0 排一次联网同步（`sdkmanager` 装 platforms;android-37 与 build-tools、下载 CMP 安卓构件）。JDK：Homebrew OpenJDK 17。核查员的实验工程只保存在本机（`~/Coach/verifier-artifacts/d5/`，不入仓库），结论已写进 ADR-0001。

依赖选型照 C#18：SQLDelight 2（版本钉到已合入 sqldelight#5986 的版本，方言钉 `sqlite-3-38`，S0 在模拟器上跑 `select sqlite_version()` 记录实测值）〔评R3 m1，d8 建议 3〕、Ktor client + WebSocket、navigation-compose、KMP ViewModel + StateFlow、手写 DI；Markdown 用 `org.jetbrains:markdown` 解析 + 自写渲染器。

### 7.2 本地库与搜索（决定）〔d8〕

客户端用 SQLDelight + 系统 SQLite 存账本副本、投影缓存、消息、公式 SVG、离线包、发件箱、草稿。Android 系统 SQLite 在任何 API 级别都没有 FTS5 → **M1 搜索只走服务端**；离线搜索与打包 SQLite 推到 M2。

### 7.3 状态与数据流（决定）〔O §7.3〕

进程级 `SyncEngine` 持有 WS、发件箱、本地账本副本；投影由 `shared/domain` 在客户端本地算；界面只观察 SQLDelight Flow；未确认命令作「待定覆盖层」显示。草稿 300ms 防抖落库，另在 `onPause/onStop` 与窗口失焦时立即落库 〔评A §2〕。

### 7.4 公式与流式 Markdown（决定）〔d1，见 ADR-0005〕

- 服务端 `mathsvc`：Node 22 + `mathjax-full@3.2.2`，`fontCache:'none'`，常驻，JSON 行协议；输出去掉 `data-*`/aria；尺寸用 em；按 tex 的 sha 缓存。
- 客户端 `MathSvgPainter`（commonMain）只支持子集：`svg / g / path / rect / text`；translate/scale（逗号或空格）；嵌套 svg 按 x/y 平移、按宽高裁剪、按 viewBox 映射；颜色 `currentColor` 绘制时取主题色；按 sha 缓存解析好的 Path；中文 `<text>` 用平台字体回退并按 MathJax 估宽横向缩放。
- 行内基线：`PlaceholderVerticalAlign.TextCenter`，客户端用 TextMeasurer 算补边（`c = 基线 − (行顶+行底)/2`，`H = 2·max(h−c, d+c)`，基线画在离顶 `H/2+c`）；Fold8 与 Mac 截图差分 ≤1dp。
- 流式：AST → 块列表；已封口块不可变、稳定 key；delta 50ms 合帧，只重解析尾块；**不支持引用式链接定义**（后置定义会改写前文，CommonMark §4.7），出现时按纯文本显示 〔评A §5〕。公式规范化只作用于已封口的块。
- 货币 `$`（漏#1）：分词器用 Pandoc 的 `$` 规则（开 `$` 后不能是空白、闭 `$` 前不能是空白且后面不能紧跟数字）；模型提示要求公式只用 `\(\)`/`\[\]`；SC 的 bare-math 修复只对模型生成文本跑、不对资料原文跑；移植 bare-equation-policy 时修掉「`<br>` 被包进 `$`」；课程库 386 行含 `$数字` 的原句进黄金用例，误判数必须为 0。
- 离线：消息里的公式 SVG 随消息落本地库，不做会把旧消息公式变空白的 LRU 淘汰；SVG 缺失时显示 LaTeX 源码 chip（并计入渲染失败数）〔评S §2〕。
- 渲染一致性的比对方法与 Android 必交截图见 ADR-0005 验证 〔评R3 I11〕。
- 退路：Android 块级 WebView + 打包 KaTeX（最后一级）；桌面不用 KCEF/JCEF（已归档、首启下载、弹层被遮），退化为源码 chip 并单独计失败。

### 7.5 手机输入公式（决定）〔漏#10〕

计算题与 Analytical 题的输入栏上方加一排符号键（− × ÷ ^ √ π ≤ ≥ ≈ ½ 下标），输入纯文本约定（`0.25`、`-11/3`、`x^2`、`sqrt(x)`），在线时下方实时预览渲染结果；批改与数值比对接受这套纯文本和常见中文数字（「负十一分之三」）。不做手写识别、不做图片作答（M1 不做）。

### 7.6 组字回车（决定）〔d5 建议 4〕

只在 KeyDown 发送，从不在 KeyUp 触发；发送前检查 `composition == null`；Shift+回车换行；手机上回车换行。S1 真机由他本人在豆包输入法和系统拼音下各测一遍（多字组字回车、单字母组字回车、Shift+回车、数字选词），`gradlew run` 与打包后 `.app` 都测。

## 8. 自适应与折叠屏（决定）〔O §8，评O §6.1：O 断点 + S 窗格算法〕

- 按**窗口** dp 与姿态，不按机型。断点、短高规则（高 <480 优先）、Medium/Expanded/Large 布局照 `O §8.1`；对话栏宽度下限 360dp，侧栏在空间不够时先收起再压缩对话 〔评A §2〕；窗格分配用 `S §8` 的最小窗格算术 + `movableContentOf` 迁移内容。
- 状态保持：Activity 自处理配置变化；状态在 ViewModel + 进程级 SyncEngine；进程被回收后从本地库恢复（`configChanges` 不能代替进程恢复，S1 真机测后台回收）〔评A §7〕。
- 半折叠桌面姿态推到 M3；书本姿态的铰链避让 M1 做（分割线对齐铰链）。
- S1 第一件事：在 Fold8 与 Pad Mini 真机打印实际窗口 dp 与 FoldingFeature，替换估算值。OPPO Pad Mini 需先装 Tailscale 并入 tailnet。

## 9. 设计系统（决定）〔O §9，C#19〕

照 `O §9`：SC 语义层令牌 → `CoachColors` + M3 `ColorScheme`；`fgTertiary`/`accent` 不承载信息文字（对比度测试是用户可感知质量，保留为 commonTest）；暗色 tertiary 调到 `#8f8a80`；动效跟随系统「减少动态效果」。KP 行状态加文字与图标，不只靠颜色 〔评A §1〕。安卓标题字体（打包宋体子集 vs 全无衬线）是品味选型，M1 出两张截图后再请他选，M0 不阻塞。

## 10. 分发与签名（决定）〔漏#9，d5 建议 5〕

- Mac 自用：`createDistributable`/jpackage 出 app-image（自动 ad-hoc 签名，本地构建无 quarantine，直接打开）。Compose 插件打包任务首次要联网取一个 probe 构件。不做 dmg、不公证；给其他 Mac 用时再定（Developer ID 要动他的付费开发者账号 → 那时走拍板）。
- Android：沿用 `~/.android/debug.keystore` 签名，新包名 `local.coach` 与 DeepTutor 壳并存；自更新 = 同签名 + versionCode 递增 + 服务端 `/app.apk` 下载（版本不兼容时客户端引导更新，沿用现手机入口做法）。

## 11. DeepTutor → Coach 迁移（决定）〔C#17，d8，见 ADR-0006〕

工具 `tools/migrate-deeptutor`，映射表照 `O §10`，修改：
- **读法**：期中前（≤2026-11-02 14:25）不对 DeepTutor 数据目录开任何连接：在 DeepTutor 空闲时把库文件**拷贝**到暂存目录：拷贝前后对 db 和 `-wal` 各做一次 stat（size + mtime），一致才算有效拷贝，否则重拷；不拷 `-shm`；`courses.json`、`journal.json`、`notebook/` 在同一个空闲窗口里一起拷 〔评R3 m6〕；对拷贝做 `PRAGMA integrity_check` 后再读。期中后：`file:...?mode=ro` 打开 + `VACUUM INTO` 出快照，所有读取对快照做；幂等键用快照 sha256。不用 `?immutable=1`。最终切换那次先停 DeepTutor 或确认空闲，再核对跨库关联。
- **稳定源 ID**：每条源记录用源库主键作 `legacy:{source}:{table}:{pk}`（与 ADR-0006 决定 3 一致），源记录内容变了 → 生成修订而不是第二份学习事实 〔评A §2、§5〕。
- 17/20 条作答：以 `mastery_interactions` + `mastery_events`（20 条）为准；缺证据的 3 条标「大纲合并时重置」，列进对账报告。全部 20 条迁移作答都不进任何窗口（§2.1），只用于 `legacy_passed` 锁存与展示 〔评R3 I4〕。
- 108 题归属 KP：章 → 模块 → 页码重叠 → 模型选 1–2 个 KP 并盲复核；60 道选择题逐批盲复核入池；48 道简答由模型生成 rubric → 异厂复核 → 他抽查 10 题（M1）。
- 5 个已通过 KP → `legacy_passed`；5 条复习状态 → `legacy_schedule`。
- `topic_dd61…`「期中冲刺」路径（62 KP、0 作答）不导入，原 JSON 存档（可逆）。
- 期中分值覆盖表 → `exam_weights_set`。
- 「资料」「答案」目录与 sealed 不进索引、不进导师上下文；资料目录里哪些可给导师看，M1 迁移前列清单让他确认（不是拍板项，是事实确认）。
- 对账报告：49 KP、108 题（60/48）、20 条作答（17 + 3 重置）、5 条复习、12 条笔记、5 个已通过；每个差异给出解释。另加一行核对：`legacy_schedule` 里 KP 不在 `legacy_passed` 的，不进复习队列，逐条列出 〔评R3 m8〕。

### 11.1 切换与回退（决定）〔漏#4〕

- 期中后（11-03 起）跑第一次正式迁移；之后 DeepTutor 继续可用，迁移每次增量重跑（按稳定源 ID 去重），直到他选定切换日。
- 切换日不放在任何考试前 7 天内；切换前连续 2 次增量迁移对账零差异。
- 切换后 DeepTutor 原样保留两周不动（8790、库、补丁都不碰），他随时可以回去用；Coach 不写 DeepTutor，所以回退零成本。回退后 Coach 期间新产生的学习事实不会回写 DeepTutor（已知局限，`events.jsonl` 可读）。

## 12. 测试与验证（决定）〔O §11，C#15〕

- 领域属性测试（commonTest，两端共用）照 `O §11.1`，改：「同一事件集合任意到达顺序（含重复、晚到）投影一致」〔评S D2〕；加「复习结果不撤销已通过」「assisted 作答永不进稳固」「点过提示的作答永不进稳固」〔评R3 I2〕。
- 黄金用例照 `O §11.2`，改为 3 窗口语义，加：货币 `$`、中文短答（「逆向选择」「0.25」「无」「负十一分之三」）、夏令时（Europe/London 10-25 已切换，逻辑日边界）、离线包不劫持「继续」、回档纪元。账本卡另加 〔评R3 F1、I4、I5〕：备份早于离线包下载（重放后按快照补写 posed，或存为孤儿作答）；备份之后还有在线已确认的作答（回档后重放，计数不少）；LEGACY 作答永不进任何窗口（含 108 题复核通过之后）；「作废」：已锁存的「已通过」在作废事件处撤销（支撑不足 3 条时）并写原因，复核晚到时锁存落在复核事件处。逐字证据黄金用例沿用核查员的 39+24 条样本（本机 `~/Coach/verifier-artifacts/d6/`）；含他真实作答的样本只放本机语料目录（§12.1），仓库里只放合成样例。
- UI 只测他感受到的质量：首字时间与点按数、流式不跳、公式正确、四形态切换不丢、离线 1 秒内可用、掉帧率、对比度。数字只报告，失败当 bug。
- AI 链路只认 live 端到端（`./gradlew liveTest`，不进默认构建）：真实 CLI + 真实课程页，含植入错键、伪证据、中途断流、取消的故障注入；验收者自己复跑；生产组合根里没有 mock。
- 不写锁行号、锁条数、例外台账、只验证门禁自身的负向夹具。

### 12.1 公开仓库与本机语料（决定）

仓库 `tonysuper666-creator/coach` 是**公开**的。以下内容只放本机，不进仓库：课程原文与教材页、真题与 108 题全文、他的作答与对话、DeepTutor 库的任何副本、本机绝对路径、Tailscale IP 与 tailnet 名、凭证、classpath/环境转储。
- 真实语料放 `~/Coach/corpus/`（按 spike 分子目录，每个文件写明来源与生成命令）；仓库里只放语料**加载器**、生成脚本（路径用环境变量或 `~` 相对路径）和**合成样例**。live 测试与验收读本机语料；没有本机语料时测试明确报「缺语料，未运行」，不假装通过。
- 本机配置 `~/Coach/config/local.properties`（Tailscale IP、tailnet 名、端口），构建与运行时注入；仓库只放 `local.properties.example`。
- 核查员的实验产物在 `~/Coach/verifier-artifacts/`，结论已写进各 ADR。
- 每个 PR 提交前自查：`git diff origin/main --stat` 里没有语料、数据库、`*.db`、日志、截图里的私人内容；`git grep -nE '/Users/|100\.[0-9]+\.[0-9]+\.[0-9]+|\.ts\.net'` 无命中（示例文件里的占位符除外）。

## 13. 里程碑

- **M0（约 1 周，本卡拆出）**：S0 工具链 + 公式服务、渲染、桥副本、出题与批改、账本与离线、迁移对账六项 spike（桥副本与公式服务不依赖 S0，可与 S0 并行；其余四项等 S0）；每项给数字，失败走退路。期中前只在他不学习时跑 live。文中 spike 编号沿用 `S §12`：S1 = 渲染 spike，S2 = 经桥真流式。M0 卡的依赖以 metadata `depends_on`/`unblocks` 为准（Mika 维护）：出题与批改卡 TON-1041 只等 TON-1037，桩阶段不等 TON-1038 done，live 阶段用 TON-1038 交付的 8791；stage 屏障只影响父卡唤醒，下游提升按 `unblocks` 走 〔评R3 I9〕。
- **M1 第一张卡：Ktor ↔ 桥 ↔ WS 流式链路**〔评R3 I10〕（不扩 M0）：首字 p50 的验收口径是**客户端看到正文首字**，这张卡在客户端复测（TON-1038 在桥的 SSE 层量的数只作参考）；`msg.snapshot` 续传、`(msg,i)` 去重、`msg.end.status` 与 `x_coach` 对应、取消下发后杀进程组、断网 10 秒重连后文本 sha 一致，都归这张卡；§14 #7 的 Ktor 引擎选型在这张卡里定。
- **M1（3–4 周）**：ECNM10119 单课程、安卓 + Mac 闭环：六屏、两层掌握、统一复习（含考前压缩）、预生成开讲与题池、离线包即时反馈、日记、随口问两个动作、导师按需查原文（§5.1 两级）、配对、可重复迁移、四形态、分值口径的「学了多少」。验收 = 立项卡四行的可度量目标：连续 7 天只用 Coach、首字 p50 ≤5 秒（追问/问答，n≥20）、打开到学习 ≤3 次点按、每个掌握变化可下钻到原题原答。
- **M2**：多课程导入、模考与双轨、Grok 导师、离线搜索（打包 SQLite）、前置编辑器、按 M1 的触发率与合格率调整查原文的触发阈值。
- **M3**：Windows、到期复习本地通知、半折叠桌面姿态、迁机文档、无障碍。

### 13.1 不做（v1）

课程导入界面（只有迁移工具）；PDF/PPTX 解析与页图；全局阶段机；课程版本不可变机制；图片/手写作答；导师 tool-calling 与自由代理；多用户、云同步、VPS；iOS；通知与小组件；伙伴/写作/看板/深度研究；调度参数自动优化；防休眠；`tailscale serve` HTTPS；Mac 公证与 dmg；任何「行数/门禁数」类治理。

### 13.2 执行约束（所有卡）

- 不动 DeepTutor 的运行文件（8790 桥、数据库、补丁）。期中 2026-11-02 14:25 前它必须稳定。
- CPU：大构建与模拟器 `nice -n 15`；Gradle `--max-workers=2`；全机同一时间最多一个重构建（`tools/dev/heavy` 锁，S0 提供）；11-02 当天 00:00–15:00 不跑模拟器、Gradle 全量构建与 live AI 测试。
- 额度：不新增花钱；Claude 订阅也供他本人用 → 工程主力 Codex/CodeBuddy；Expert、Sage 只点名；Antigravity 不进 M0。期中前 Coach 的 Claude 物理调用每日 ≤60 次、11-01 18:00 至 11-02 15:00 不跑 Claude live、8791 测完按 PID 停（§5.4）〔评R3 I8〕。
- 交付走 GitHub PR（§13.3），公开仓库纪律见 §12.1。
- 每张卡交付首行「结果：达到/部分达到/未达到 + 数字证据」，附本卡 `ai_calls` 调用数。

### 13.3 分支与 PR

分支从最新 `origin/main` 切出（`multica repo checkout https://github.com/tonysuper666-creator/coach`，或 `git worktree add ~/dev/coach-wt/<slug> -b m0/<slug> origin/main`）；开 PR 一律 `--base main`；PR 描述首行写「结果：达到/部分达到/未达到 + 数字证据」。done 判据照团队规则 §4 三条（已合并、base=main、head 是 origin/main 的祖先）。验收者拉 PR head 在干净 worktree 复跑；通过后合并。串行依赖用 `depends_on` 排队，不靠改 base。

## 14. 开放问题

| # | 问题 | 谁答 | 何时 |
|---|---|---|---|
| 1 | ~~导师能否自己查原文~~ | 已决：导师自动判断、有必要才翻（§5.1） | 2026-10-29 |
| 2 | 12 月是否有期末、哪天（决定考前压缩何时再生效） | 李政，M1 迁移时顺带问 | M1 |
| 3 | 48 道简答 rubric 抽查 10 题 | 李政本人 | M1 迁移时 |
| 4 | 「资料」目录哪些可给导师看 | 李政确认清单 | M1 迁移前 |
| 5 | 安卓标题字体 | 李政看两张截图选 | M1 |
| 6 | 稳固与已通过的阈值、复习种子 | 按他 M1 两周的体感调参 | M1 +2 周 |
| 7 | Ktor 引擎 Netty vs CIO | M1 第一张卡（流式链路，§13）实测后定 〔评R3 I10〕 | M1 |
| 8 | 回档时，桌面端本地账本副本（几乎总在线）是否把其他设备的事件回灌给服务端 | 工程，看 M1 回档演练里还缺多少 〔评R3 F1〕 | M1 后 |
