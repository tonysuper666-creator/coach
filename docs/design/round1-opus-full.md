# Coach 完整设计 · 第一轮（Claude Opus 5.5，总设计师视角）

日期 2026-10-29。依据：任务书所列背景文档 + 只读抽查源码与本机数据（命令只读，未改任何运行文件）。标注：**决定**＝我对这一项的立场；**建议**＝需要团队/他裁决；**开放**＝我答不了。路径缩写：`SC/`＝StudyCoach 源码 `…/scratchpad/sc/src/`，`DT/`＝`~/DeepTutor/.venv/lib/python3.12/site-packages/deeptutor/`，`~DT/`＝`~/DeepTutor/`。

---

## 0. 核心赌注（一页）

| # | 成败取决于 | 我押 | 对面的选项 | 理由 | 什么结果算我押错（spike 证伪信号） |
|---|---|---|---|---|---|
| 1 | **每次点按后的等待** | **服务端驱动的结构化状态机**：服务器按学习对象确定性备课，模型只产出内容（讲解流、题目 JSON、批改 JSON）；**不用**模型 tool-calling；题池后台预生成并经异厂复核，出题零等待 | 沿用 DeepTutor 的 `<tool_calls>` 提示协议，让模型自己决定取原文、出题、判分 | DeepTutor 每轮开头有一次约 11 万字、返回工具调用的隐形调用，14–24 秒（`~DT/DeepTutor改造记录.md` §三）；桥日志显示上下文 30–50K 字时首字 3.0–4.9 秒，120–145K 字时 6.4–23.7 秒（`~DT/data/user/runtime/bridge.log`）。每个工具回合在 CLI 下都是一次新进程 + 全量对话重渲染（`~DT/tools/subscription_bridge.py` `render_transcript`）。证据规则本来就要确定性迁移，模型自称 `passed` 也一直被忽略（`DT/learning/policy.py`） | S3：≤40K 字上下文首字 p50 >5 秒；或 M1 中发现“导师需要自己找资料”的追问 >30% 答不好 |
| 2 | **公式与流式 Markdown 的观感** | **原生 Compose Markdown 渲染 + 服务端 MathJax→SVG（随流下发尺寸）**，客户端用内联图片放公式，尺寸先到、图后到，不跳 | 每条消息一个 WebView + KaTeX；或整段对话一个 WebView | 尺寸已知才能做到滚动不跳；一处渲染（Node 版 MathJax）两端一致、可离线缓存；桌面端 WebView 要带 Chromium（KCEF，体积约百 MB [估算]），列表里多 WebView 的高度是异步测出来的，必然跳 | S2：50 条课程公式有错、钉底流式时位移 >1dp、Fold8 掉帧 >5% |
| 3 | **掌握度可信又不卡冲刺** | **两层证据**：「已通过」（冲刺层，选择题可过，沿用 DeepTutor 近期加权≥0.9）与「稳固」（StudyCoach 严格资格 + 至少一题构答 + 跨两天）；AI 批改**未经异厂复核不写任何一层**（fail closed） | 只留一层（要么全严卡死冲刺，要么全松失去刻度） | 期中 58% 是 Essay+Analytical（`~DT/data/user/workspace/courses/courses.json`），单靠选择题过关练不到写作（`~DT/Essay专项训练清单.md`）；但冲刺期把全部知识点卡在严格门上，他会像 10-28 那样“答多少遍都过不了”（改造记录 §七） | M1 七天里出现“明明会了却不让过”≥3 次，或“稳固”与他自评严重不符 |

**最大的非技术风险**是他的选择而非赌注：服务端在这台 Mac，小时在线率 11–38%（`~DT/新应用立项方案v2.md` §七）。Mac 离线时客户端仍可看全部内容、做离线练习包，讲解与批改要等 Mac（§1.1、§12 R2）。
**致命问题检查**：已定决定里没有需要推翻的。需要说清的一点：Coach M1 不可能在 11-02 期中前可用，期中照旧用 DeepTutor；Coach 服务的是之后的学期（Essay 清单里列有 Dec 场次真题，12 月很可能还有考试 [未证实]）。

---

## 1. 产品与学习闭环

### 1.1 用户旅程（决定）
- **每天**：打开 → 「继续：<知识点>」（1 次点按）→ 导师立即流式开讲（无选择页）→ 「下一步」出题（题池已备好，0 等待）→ 判分 → 已通过则提示下一个。到期复习、今日日记是次级卡。目标 ≤3 次点按、首字 ≤5 秒（立项方案 §八）。
- **考前冲刺**（距考试 ≤7 天）：今天顶部显示倒计时；复习间隔被压缩（§2.4）；论述类知识点在讲完后默认建议「考试写法」；≤3 天出现「模考」卡。
- **手机碎片**（外屏 475dp）：今天的「复习 3 题」一手可做；选择题选项 ≥56dp 高；答完立刻判分，简答类先存下，复核完推送结果到日记。
- **折叠/展开切换**：外屏做到一半展开 → 同一张题卡原样放大，右侧多出原文页；输入草稿、已选选项、流式输出都不中断（§8）。
- **离线 / Mac 休眠**：客户端有账本副本、用同一份领域代码算投影，今天/路线/记录/历史对话照常可看；可做预先下载的「离线练习包」（10 道已复核题），答案入发件箱，Mac 回来后判分；讲解与批改显示「Mac 未连接（最后同步 hh:mm）」，绝不显示占位内容。

### 1.2 屏幕清单（决定）

| 屏 | 主动作（唯一） | 次要 | 空状态 | 错误态 |
|---|---|---|---|---|
| 今天 | 继续当前知识点 | 到期复习 N、今日日记、随口问输入框 | 无课程：「在 Mac 上运行迁移」；全部已通过：主按钮变「开始复习/模考」 | 离线：缓存数据 + 顶部条；服务器版本不兼容：更新页（APK 下载） |
| 路线 | 点知识点进入学习 | 模块折叠、状态筛选 | 无大纲：说明如何导入 | 同上 |
| 学习（KP 线程） | 「下一步」（服务器决定讲/出题/去下一个） | 模式药丸、追问输入、原文页、闭卷检查 | 首次进入直接开讲，不存在空状态 | 导师不可用：保留已有内容，显示真实原因与「重试」；兜底中：显示实际模型名 |
| 复习 | 开始复习（N） | 碎片模式 3 题、推迟 | 「今天没有到期」+「提前复习明天的 N 个」 | 批改不可用：作答排队，标「待批改」 |
| 记录 | 打开某天 | 日记人工区编辑、证据下钻（原题/原答/rubric/批改与复核模型） | 「今天还没学」 | 摘要生成失败：只显示统计 + 重试 |
| 资料 | 搜索 | 文档列表、页面阅读器（PDF 第 N 页 · 书第 M 页）、笔记 | 无资料 | 检索失败：重试 |

### 1.3 对话模型（决定）

| 种类 | 挂在 | 数量 | 能做 | 不能做 |
|---|---|---|---|---|
| 知识点线程 | 一个 KP | **每个 KP 恰好一条**，跨天延续、按天分段 | 讲、追问、换讲法、出题、判分、写证据 | 改别的 KP 的掌握度（多 KP 题只给主 KP 计分，其余只进复习安排） |
| 复习会话 | 一次会话的 KP 列表快照 | 每次一条 | 出题、判分、改复习安排；合格的作答可计入「稳固」 | 改「已通过」（§2.2） |
| 随口问 | 课程 + 当前 KP（只读） | 每个逻辑日一条 | 回答、建议归类、沉淀 | 出计分题、写任何掌握证据 |
| 模考 | 一份试卷 | 每次一条 | 限时作答、首做轨/修复轨 | 中途讲解（交卷后才进入讲评） |

- **追问边界**：上下文 = 本 KP 备课包 + 本线程最近 12 轮（沿用 StudyCoach 右栏的 12 轮）；只写 `followup_asked`，不动掌握度。若服务器判断问题属于别的 KP，简答后给「去学 <Y>」。**有待答题时**，线程里每次导师回复都记为一次提示（`hint_revealed cause=tutor_reply`），输入框上方提示「现在问会算作提示」——用确定性规则代替“让模型判断是不是在问答案”。
- **多位导师如何出现**：导师 = 服务器按任务分配的模型角色，不是要选的资源。讲解默认 Claude Sonnet 5.5；「换个老师讲」用同一备课包换 GPT-6.1 Sol（M2 加 Grok；Antigravity 默认关，条款风险，改造记录 §四）；批改卡显示「复核：GPT-6.1 Sol ✓」；消息头显示实际出答的模型名（含兜底）；讲解顶部列「本次参考：教材 p.8–12」——透明但不可选。
- **随口问的沉淀**：服务器先用 FTS5 给 KP 打分，分差小时才调一次模型；答案下方三个动作：「存为笔记（挂 KP X）」「加入复习（据此生成一张题，复核后入 X 的题池）」「移到 X 的学习线程」，均可撤销。

**建议**：随口问是否也允许在路线页、资料页打开（同样挂课程）。**开放**：多 KP 题的计分规则是否要按权重分摊（我选“只给主 KP”，因为简单且可解释）。

---

## 2. 学习规则

### 2.1 作答分类（决定，policy v1，纯函数，放 `shared/domain`）

```kotlin
// 已通过层的贡献值；null = 不计
fun passContribution(a: AttemptView): Double? = when {
  a.voided || a.grade == null -> null
  a.grade.state !in setOf(EXACT, VERIFIED) -> null   // AI 批改未复核：fail closed
  a.mode == REVIEW -> null                            // 复习只改安排（StudyCoach 规则）
  a.repeatWithin14Days -> null                        // 同指纹 14 天内答过
  a.attemptIndex > 1 -> null                          // 同题重试
  else -> (a.score / a.max) * (if (a.hintsUsed > 0) 0.5 else 1.0)
}
// 稳固层（规格，伪代码）：照搬 SC/packages/domain/src/mastery-evidence-policy.ts 的顺序，再加四条
fun solidDecision(a: AttemptView): Decision = TODO()
  // SC 原规则：无评估引用→UNLINKED；verdict≠pass；score/max<0.8；hints≥3；purpose 不在
  // {CLOSED_BOOK, UNSEEN_VARIANT, TIMED, FULL_MOCK}→PRACTICE；novelty=REPEATED；attempt≠1
  // Coach 追加：grade.state∉{EXACT,VERIFIED}→PENDING；单选题且 purpose∉{TIMED,FULL_MOCK}→PRACTICE；
  //            离线作答不可能是 TIMED；origin=LEGACY→LEGACY（只记覆盖，不算客观证据）
```
`purpose` 由**用户显式发起的活动**决定，不靠猜：「练一题」= practice；「闭卷检查」（隐藏原文栏、折叠对话历史、用户点“开始闭卷”）= closed_book；复习里首次见到的题 = unseen_variant，见过的 = recall_card；模考 = full_mock/timed。

### 2.2 两层如何共存（决定）

| | 已通过（冲刺层） | 稳固（严格层） |
|---|---|---|
| 公式 | passScore = DeepTutor 近期加权（权重 0.5/0.7/0.85/0.95/1.0，1 次封顶 0.5、2 次封顶 0.8，`DT/learning/mastery.py`）≥ **0.9**（`DT/learning/policy.py` QUANTITATIVE_GATE） | 合格证据 ≥2 条，**其中 ≥1 条为构答**（简答/论述/计算），分布在 **≥2 个逻辑日**，平均强度 ≥ **0.85**（SC `projections/src/mastery.ts` 的 demonstrated 线） |
| 选择题 | 可过（3 连对即过） | 只有限时/模考里的选择题合格（单选 5 选 1 有 20% 猜中率） |
| 提示 | 有任何提示，该次按半分 | 沿用 StudyCoach：提示 <3 次 |
| 锁存 | 首次达到后锁存；之后答错不撤销，只标「需回炉」并明天进复习 | 最近一次合格情境作答失败 → 暂停（显示为“曾稳固”），直到新的合格通过 |
| 影响 | 路线推进、「继续」去哪 | 路线徽章、记录页统计、复习间隔种子（稳固的种子更长） |
| 不变量 | 稳固 ⇒ 已通过（定义里 passed := passScore≥0.9 ∨ solid ∨ 手动标记） | 手动标记永远不产生稳固 |

理由：保留他已习惯的冲刺门（`~DT/tools/relax_gates.py` 之后的行为），StudyCoach 的严格刻度作第二层；“跨两天”对应 DeepTutor“同场练习不算持久提取”（`DT/learning/scheduler.py` `_SAME_SESSION_DAYS=0.25`）。
**偏离 StudyCoach**：资格看活动 purpose 而非阶段；复习作答可计入稳固（延迟提取是最强证据），但不改「已通过」。**偏离 DeepTutor**：已通过锁存，不随后续错题上下跳；有提示按半分。

### 2.3 前置依赖、阶段（决定）
- **前置依赖**：软锁。`next()` 跳过前置未「已通过」的 KP；锁住的 KP 仍可从路线点开，顶部写「建议先学 X」。理由：DeepTutor 选目标不看前置（`policy.next_objective`），StudyCoach 硬锁（`SC/packages/domain/src/mastery.ts` `recommendModule`），冲刺期硬锁挡路。大纲修订做环检测。现 `prerequisite_ids` 全空，迁移时模型提议、他确认。
- **阶段**：**不保留**为全局状态机（StudyCoach 五阶段 `SC/packages/domain/src/learning-phase.ts`）。保留它的两个真正有用的效果：时间不推进任何状态；模考首做分与修复分**双轨永不合并**（照搬 `SC/packages/domain/src/score-track.ts` 的先写者赢/最新者赢）。理由：阶段要求“首轮全部完成才能深攻”，对一个 49 点、4 天后考试的课程只有摩擦；资格已经由 purpose 精确表达。

### 2.4 复习队列（含题库）合一（决定）
- **队列项 = KP**，不是题。题是“载体”：开始复习时按 ① 本 KP 答错且未再答对的题 ② 未见过的题库题 ③ 已复核的生成变式 ④ 最久未见的旧题 取一张。108 题因此全部经由 KP 进入复习（今天它们的复习状态是 0 条，对比方案 §四）。
- **调度**（纯 fold）：首次已通过 → 间隔 1 天、ease 2.3（稳固时种子 3 天；第二天的首次提取最值钱，所以不用 StudyCoach 按强度给 7 天的种子）；每个 KP 每逻辑日只有第一次复习作答参与调度。PASS（≥0.8）：间隔×ease，ease+0.1（≤3.0）；NEEDS_WORK（0.5–0.8）：间隔×0.5，ease−0.15；FAIL（<0.5）：间隔 1，ease−0.2（≥1.3），lapses+1，标需回炉（更新参数取自 `SC/packages/projections/src/review-queue.ts`）。学习模式里答错已通过的 KP → 到期日提前到明天。
- **考前压缩**：考试在未来时，间隔上限 = max(1, ⌊(距考天数−1)/2⌋)，保证考前至少再见一次。
- 排序：逾期天数降序 → lapses 降序 → 大纲序。默认会话 10 项，碎片模式 3 项。
- **建议**：调度先用上面的简单 ease 模型而不是移植 DeepTutor 550 行的保持率模型（`DT/learning/scheduler.py`）；迁移来的 5 条复习状态按其 `next_review_at` 直接接管。

### 2.5 日记结构（决定）
逻辑日以课程时区 04:00 为界（熬夜算前一天）。`JournalDay = 统计（学习分钟：相邻事件间隔<10 分钟累加；打开/讲完的 KP；新已通过；新稳固；作答总数/正确；复习 pass/needs_work/fail；追问；笔记） + AI 摘要（亮点≤3、卡住的点链接到作答、明天建议链接到 KP；记录 fromSeq，有新事件即标“已过时”） + 人工区（Markdown，rev 乐观锁） + 迁移区（DeepTutor 旧日志）`。摘要在打开该天或次日首次启动时后台生成（对应 `SC/packages/projections/src/daily-journal-day.ts` 的计数结构）。

### 2.6 教学模式触发（决定；模式表照搬 `SC/packages/domain/src/teaching-modes.ts` 九种）

| 模式 | 自动建议（药丸带点，不擅自切换） | 自动执行 |
|---|---|---|
| 当前讲（默认） | — | 首次进入 KP 直接开讲 |
| 直觉讲 / 换例子 | 本 KP 连续答错 2 次 / 任一次答错 | — |
| 正式讲 | KP 类型 procedure 或含公式 | — |
| 考试写法 | 距考试 ≤7 天，或 KP 考法含 essay（名称带【Essay】） | — |
| 单元地图 | 进入模块第一个 KP | — |
| 短诊断 | KP 迁移时已通过但在 Coach 里从未讲过（“先测一下再决定讲不讲”，对应 DeepTutor 的 probe） | — |
| 费曼复述 | concept/design 类讲完后 | 作答按构答批改 |
| 本步复习 | 复习会话里点「讲一下」 | — |

**开放**：14 天重复窗口、0.85/0.9 阈值是否在 M1 两周后按他的体感校准（只调参，不改结构）。

---

## 3. 领域模型与事件账本

### 3.1 一等对象（决定，`shared/domain`，可直接落 Kotlin）

```kotlin
@JvmInline value class Id(val v: String)               // ULID；设备离线时可本地生成命令 ID
data class Course(val id: Id, val code: String, val title: String, val tz: String,
  val dayStartHour: Int = 4, val examAt: Instant?, val instructionsMd: String)
data class Module(val id: Id, val order: Int, val title: String)
data class KnowledgePoint(val id: Id, val moduleId: Id, val order: Int, val title: String,
  val kind: KpKind,                                     // MEMORY CONCEPT PROCEDURE DESIGN
  val examFormats: Set<Format>, val prerequisites: Set<Id>,
  val anchors: List<SourceAnchor>, val retired: Boolean, val legacyId: String?)
data class SourceAnchor(val docId: Id, val pdfPage: Int, val bookPage: Int?, val quote: String?)
enum class Format { SINGLE_CHOICE, SHORT_ANSWER, ESSAY, CALCULATION }
data class Question(val id: Id, val kpIds: List<Id> /*首个为主 KP*/, val format: Format,
  val stemMd: String, val options: List<Option>, val origin: Origin /*BANK GENERATED LEGACY FROM_QUICK_ASK*/,
  val fingerprint: String /*sha256(规范化题干+选项)*/, val keyState: KeyState /*VERIFIED UNREVIEWED REJECTED*/)
sealed interface LearningObjectRef { data class Kp(val id: Id): LearningObjectRef
  data class ReviewSession(val id: Id): LearningObjectRef; data class CourseRef(val id: Id): LearningObjectRef
  data class MockPaper(val id: Id): LearningObjectRef }
data class Thread(val id: Id, val kind: ThreadKind, val anchor: LearningObjectRef)
// 只存在于 server/core，不进 shared/protocol —— 编译期保证标准答案到不了客户端和导师上下文
data class AnswerKey(val questionId: Id, val option: String?, val numeric: NumericKey?,
  val rubric: List<Criterion>, val modelAnswerMd: String)
data class Criterion(val id: String, val text: String, val points: Int, val basis: List<SourceAnchor>)
```

### 3.2 事件（决定）
信封：`Event(seq: Long /*服务器分配，课程内连续*/, id, courseId, type, schema: Int, recordedAt, occurredAt /*设备时间，≤recordedAt*/, deviceId, actor /*LEARNER TUTOR(model) SYSTEM MIGRATION*/, idemKey /*课程内唯一*/, causationId?, threadId?, payload)`。对话正文**不进账本**（存 `messages` 表，像 StudyCoach 把人工日记当事实表一样，`SC/docs/architecture/adr-0001-single-projection-surface.md`），账本只记学习事实，重放保持小而快。

| type | 关键字段 | 幂等键 | 产生者 | 撤销 |
|---|---|---|---|---|
| outline_revised | ops[add/rename/move/retype/setPrereq/retire], outlineHash | `outline:{hash}` | 迁移/学习者（Mac） | 再修订；KP 只退役不删 |
| exam_set | examAt | 命令 | 学习者 | 覆盖 |
| kp_opened | kpId, via | 命令 | 学习者 | 否 |
| explanation_delivered | kpId, msgId, mode, model, citedAnchors[], coverage(FULL/PARTIAL) | `msg:{id}` | 系统（流结束+引用校验后） | 否 |
| followup_asked | threadId, msgId, kpId?, pendingAttemptId? | `msg:{id}` | 学习者 | 否 |
| question_posed | attemptId, questionId, kpIds, purpose, novelty, mode, policyVersion, timeLimitSec?, offlinePack | `attempt:{id}` | 系统 | attempt_voided |
| hint_revealed | attemptId, level, cause(BUTTON/TUTOR_REPLY) | `attempt:{id}:hint:{n}` | 学习者/系统 | 否 |
| answer_submitted | attemptId, answer, offline | `attempt:{id}:answer`（一次作答只此一条） | 学习者 | 否 |
| attempt_graded | attemptId, grader(EXACT/AI), model?, verdict, score, max, rubricResults[criterionId, awarded, studentEvidence], confidence | `attempt:{id}:grade:{n}` | 系统 | 被复核修订 / 作废 |
| grade_reviewed | attemptId, reviewer, decision(CONFIRM/REVISE/UNCERTAIN), revised? | `attempt:{id}:review:{n}` | 系统 | 否 |
| attempt_voided | attemptId, reason(KEY_WRONG/DISPUTE_UPHELD/DUPLICATE) | `attempt:{id}:void` | 学习者/系统 | 终态 |
| kp_overridden / override_cleared | kpId, kind(MARK_PASSED/SKIP/DEFER/REOPEN), reason | 命令 | 学习者 | cleared |
| review_snoozed | kpId, untilDay | 命令 | 学习者 | 再推 |
| note_saved / note_deleted | noteId, kpId?, fromMsgId?, contentSha | 命令 | 学习者 | deleted |
| quick_ask_filed | msgId, kpId, action(NOTE/REVIEW_CARD/MOVE) | 命令 | 学习者 | 反向事件 |
| journal_edited | day, rev, contentSha | `journal:{day}:{rev}` | 学习者 | 新 rev |
| journal_summarized | day, fromSeq, model, contentSha | `summary:{day}:{fromSeq}` | 系统 | 新一次 |
| mock_started / mock_submitted | mockId, paperId, limitSec | `mock:{id}:…` | 学习者 | 否 |
| legacy_imported | source, sourceHash, counts | `legacy:{hash}` | 迁移 | 否 |
| legacy_attempt / legacy_schedule | kpId, occurredAt, value / dueAt, interval, reviewCount, lapses | `legacy:{sourceRef}` | 迁移 | attempt_voided / 被新复习覆盖 |

证据**不是事件**而是投影：由 posed+submitted+graded+reviewed 经 `question_posed.policyVersion` 指定的策略版本算出，策略升级不会悄悄改写旧作答（这解决了 StudyCoach 需要 `evidence_reclassified` 补偿事件的问题）。

### 3.3 投影（决定，全部是 `fold(events) → State`，不读“现在”，只有“今天到期”过滤读当前逻辑日）
- **Position**：`current` = 最后一个 via≠REVIEW/QUICK 的 `kp_opened`；`pending` = 每线程最近一个无答案、未作废的 `question_posed`；`continueTarget = pending?.kp ?: current(若未通过) ?: next()`；`next()` = 大纲序第一个「未通过、未推迟/跳过、前置全通过」的 KP，全被锁住时退回第一个未通过。到期复习**不抢**「继续」（偏离 DeepTutor `next_objective` 第 2 步，贴近 StudyCoach“位置来自续接”）。
- **Mastery(kp)**：`passScore, passed(锁存), passedBy(SYSTEM/LEARNER/LEGACY), solid, solidProgress(合格数, 构答数, 天数, 均值), suspended, needsRepair, evidenceRefs` —— 定义见 §2.2。
- **Review(kp)**：`dueDay, intervalDays, ease, lapses, lastResult` —— §2.4。
- **Journal(day)**：§2.5。**ScoreTrack(paper/question)**：首做/修复双列。

### 3.4 版本化与迁移（决定）
每个事件类型独立 `schema`；`shared/domain` 里写上抛器链 `upcast(raw) → DomainEvent`（v1→v2→…），**不改写已落库事件**。投影有 `PROJECTION_VERSION`，不匹配就丢缓存全量重放。SQL 结构用编号迁移 `V001__init.sql…` 启动时在事务里执行，执行前自动备份。

**建议**：事件 payload 用 kotlinx.serialization 的 sealed class 多态（`type` 判别字段），未知类型在客户端保留原文不崩溃。**开放**：对话正文不进账本，意味着“某次讲解说了什么”只能从 messages 表查，不能从事件重放——我认为可接受（讲解不是学习事实），评审可挑战。

---

## 4. 服务端与存储

### 4.1 模块与依赖方向（决定）
```
shared/domain   ← 零 IO（kotlinx-serialization、kotlinx-datetime）
shared/protocol → domain        （DTO、命令、WS 帧、版本号）
server/store    → domain        （SQLite、迁移、备份）
server/content  → domain, store （导入、分页、FTS5、内容寻址）
server/ai       → domain        （Provider、任务、校验器、mathsvc 客户端）
server/core     → domain, protocol, store, content, ai （用例、单写者、租约）
server/app      → core          （Ktor 路由、WS、认证；唯一组合根）
tools/migrate-deeptutor → core, store
```
单写者：所有写命令经 `core` 里一个协程 actor 串行执行，事件 + 投影缓存 + 命令结果在**同一个 SQLite 事务**提交。

### 4.2 SQLite 表（决定，WAL）
```sql
CREATE TABLE events(seq INTEGER PRIMARY KEY, id TEXT UNIQUE, course_id TEXT, type TEXT, schema INT,
  recorded_at TEXT, occurred_at TEXT, device_id TEXT, actor TEXT, idem_key TEXT, causation_id TEXT,
  thread_id TEXT, payload TEXT, UNIQUE(course_id, idem_key));
CREATE TABLE command_log(device_id TEXT, command_id TEXT, result TEXT, at TEXT, PRIMARY KEY(device_id, command_id));
CREATE TABLE blobs(sha256 TEXT PRIMARY KEY, size INT, mime TEXT, path TEXT);          -- 原件 PDF 等，内容寻址
CREATE TABLE source_docs(doc_id TEXT PRIMARY KEY, course_id TEXT, kind TEXT /*textbook slides notes reading review_book*/,
  title TEXT, blob_sha256 TEXT, book_page_offset INT /*教材：书页 = PDF 页 − 31*/, tutor_visible INT);
CREATE TABLE source_pages(doc_id TEXT, pdf_page INT, book_page INT, text TEXT, text_sha256 TEXT, PRIMARY KEY(doc_id, pdf_page));
CREATE VIRTUAL TABLE fts_en USING fts5(text, doc_id UNINDEXED, pdf_page UNINDEXED, tokenize='porter unicode61');
CREATE VIRTUAL TABLE fts_zh USING fts5(text, doc_id UNINDEXED, pdf_page UNINDEXED, tokenize='trigram');
CREATE TABLE outline_kp(kp_id TEXT PRIMARY KEY, course_id TEXT, module_id TEXT, ord INT, title TEXT, kind TEXT,
  exam_formats TEXT, prereqs TEXT, anchors TEXT, retired INT, legacy_id TEXT);      -- outline_revised 的物化，可重建
CREATE TABLE questions(question_id TEXT PRIMARY KEY, course_id TEXT, format TEXT, stem_md TEXT, options TEXT,
  origin TEXT, fingerprint TEXT, key_state TEXT, created_seq INT);
CREATE TABLE question_kp(question_id TEXT, kp_id TEXT, is_primary INT, PRIMARY KEY(question_id, kp_id));
CREATE TABLE answer_keys(question_id TEXT PRIMARY KEY, key_json TEXT, reviewed_by TEXT, reviewed_at TEXT);
CREATE TABLE threads(thread_id TEXT PRIMARY KEY, course_id TEXT, kind TEXT, anchor_type TEXT, anchor_id TEXT,
  UNIQUE(kind, anchor_type, anchor_id));   -- 每 KP 一条线程由此保证；随口问的 anchor_id = '{course}:{逻辑日}'
CREATE TABLE messages(thread_id TEXT, mseq INT, message_id TEXT UNIQUE, role TEXT, model TEXT, mode TEXT,
  status TEXT /*streaming complete truncated cancelled failed*/, content_md TEXT, citations TEXT, created_at TEXT,
  PRIMARY KEY(thread_id, mseq));
CREATE TABLE math_svg(sha256 TEXT PRIMARY KEY, tex TEXT, display INT, svg TEXT, w_ex REAL, h_ex REAL, v_align_ex REAL);
CREATE TABLE turn_leases(thread_id TEXT PRIMARY KEY, turn_id TEXT UNIQUE, device_id TEXT, expires_at TEXT);
CREATE TABLE projection_cache(name TEXT, course_id TEXT, version INT, last_seq INT, blob BLOB, PRIMARY KEY(name, course_id));
CREATE TABLE ai_calls(call_id TEXT PRIMARY KEY, task TEXT, requested_model TEXT, served_by TEXT, ttft_ms INT,
  dur_ms INT, in_chars INT, out_chars INT, status TEXT);                              -- 只记长度耗时，不记内容（同桥日志原则）
CREATE TABLE devices(device_id TEXT PRIMARY KEY, name TEXT, token_sha256 TEXT, created_at TEXT, revoked_at TEXT);
```
- 教材页切分依据现有标记 `<!-- PDF 第 39 页 · 书第 8 页 -->`（`~DT/library/ECNM10119-健康经济学/教材/*.md`）。引用显示为「教材 p.8（PDF 39）」。
- 双分词器：英文教材用 porter 词干 + BM25；中文笔记用 trigram（需 ≥3 字）。两表并查合并。**[未证实]** sqlite-jdbc 自带 FTS5 与 trigram（需 SQLite ≥3.34），S5 验证。
- 备课包哈希 = sha256(排序后的锚点 + 页文本哈希)，作为讲解缓存与题目生成的键。

### 4.3 备份（决定）
每晚 03:30（若醒着，否则下次启动）`VACUUM INTO ~/Coach/backups/coach-YYYYMMDD.sqlite`，保留 14 日 + 8 周；同时导出 `events.jsonl`（人可读、与工具无关）；每次结构迁移和数据迁移前强制备份。验收每月做一次“恢复 → 全量重放 → 投影哈希一致”。**建议**：是否另拷到外置盘/iCloud——属于数据出机，由他定。

### 4.4 租约与多设备冲突（决定）
单用户 + 单写者服务器，冲突面很小，只保留 DeepTutor 租约思想里“一个对象同一时刻只一个在教”这一条（`DT/learning/storage.py` `mastery_path_leases`），粒度改成**线程的回合租约**（每 KP 只有一条线程，等价于每 KP 一个），流式期间每 30 秒续期，120 秒过期。

| 场景 | 处理 |
|---|---|
| 两台设备在同一 KP 线程发消息 | 第二台**实时旁观**正在进行的流（订阅），自己的消息排队到本轮结束；可选「打断」= 取消前一轮 |
| 同一 attempt 两处作答 | 先到者生效；后到者返回 409 + 已判结果；离线那份原文保留进日记，不计分 |
| 日记人工区并发编辑 | rev 乐观锁；冲突时两版并存并提示合并 |
| 离线作答时题目已作废 | 作答保留、不计 |
| 大纲修订后旧客户端引用旧 KP | KP 只退役不删除，旧引用照常显示 |

**建议**：服务端用 launchd 用户代理常驻（KeepAlive），这是本机持久配置，装之前要他同意。**开放**：Ktor 引擎选 Netty 还是 CIO（我倾向 Netty，WS 更成熟），S3 实测再定。

---

## 5. AI runtime

### 5.1 编排方式（决定）：服务端驱动的结构化状态机
```
KP 流程：IDLE → PREPARE(确定性备课：KP 锚点页 ≤6 页 + 笔记 + FTS 补充，≤40K 字)
  → TEACH(流) → AWAIT(下一步/追问/模式/练一题/闭卷检查)
  → POSE(从题池取，0 等待) → AWAIT_ANSWER → GRADE(选择题精确 | AI 批改)
  → REVIEW(AI 批改的异厂复核，后台) → FEEDBACK → (已通过? 提示下一个 : 回到 AWAIT 并给模式建议)
```
「下一步」由服务器决定：未讲 → 讲；讲过未通过 → 出题；已通过 → 下一个 KP。≤6 页沿用 `MAX_LESSON_PAGES = 6`（`DT/learning/lesson_evidence.py`）。代价：导师不能自己翻更多原文；缓解：每次追问前服务器按问题再 FTS 补页；M2 加“深查原文”代理（沙箱只读课程库，沿用 `~DT/tools/sandboxed_agent.sh` 白名单）。

### 5.2 任务表（决定）

| 任务 | 流式 | 主 | 兜底 | 独立复核 | 物理调用上限 | 上下文 | 何时计入账本 |
|---|---|---|---|---|---|---|---|
| 讲解 / 追问 | 是 | Claude Sonnet 5.5（low） | GPT-6.1 Sol（非流式） | 否 | 2 | ≤40K / ≤30K 字 | 流结束+引用校验后写 explanation_delivered；追问在请求时写 |
| 出题（后台预生成） | 否，JSON | Claude | Codex | **是，异厂** | 3 | ≤20K | 复核 CONFIRM 后入池为 VERIFIED；未复核的题只能用于 practice |
| 简答/论述/计算批改 | 否，JSON | Claude | Codex | **是，异厂** | 3 | ≤15K | 立即写 attempt_graded（state=AI_UNREVIEWED，界面显示“复核中”），grade_reviewed CONFIRM/REVISE 后才计入两层 |
| 选择题判分 | — | 无模型，精确匹配 | — | — | 0 | — | 立即，EXACT |
| 换个老师讲 | 视模型 | Codex（M2 起 Grok） | — | 否 | 1 | ≤40K | 同讲解 |
| 日记摘要 | 否，JSON | Claude | Codex | 否 | 2 | ≤10K | journal_summarized |
| 随口问归类 | — | FTS5 打分，分差小才调 Claude | — | 否 | 1 | ≤4K | 不写，只建议 |

- **题池策略**：每个 KP 保持 ≥2 道已复核选择题 + ≥1 道已复核构答题未见；当前 KP、后两个 KP、今日到期 KP 优先；只在没有前台流时跑（桥里 Claude 并发 3 是共享的）。**后台每日物理调用上限默认 60（只为保护他的订阅额度，设置页可改、用量只报告）；前台永不限。**
- 批改后自评 `confidence < 0.8` 一律当 UNCERTAIN（沿用 `DT/learning/semantic_assessment.py:28`）。

### 5.3 校验（决定，全部确定性代码，模型无法绕过）
- **逐字证据批改**：移植 `SC/packages/ai-runtime/src/grade-validation.ts`——rubric 条数、条目文本、分值、依据不得改；给分条目的 `studentEvidence` 须在学生原答里有 ≥3 个词元、≥8 个有效字符、词元边界对齐的逐字片段（NFKC + 空白归一）；分数算术一致。不过 → 视为批改失败，走兜底或复核。
- **来源归属**：出题/批改 JSON 保留 StudyCoach 四类归属与范围校验（`SC/packages/ai-runtime/src/source-validation.ts`）。**流式讲解不做逐句 JSON 归属**（会毁掉流式），改为段落末尾标记 `【S3】` 引用备课包锚点 + 事后校验：未知锚点删除并记标志；带引号的引文必须在页文本中逐字出现（DeepTutor `lesson_evidence` 的做法）；无任何有效锚点 → coverage=PARTIAL。
- **独立复核**：复核者必须与主模型不同厂；输入 = 备课包 + 目标 JSON（+ 学生原答 + 固定 rubric）；REVISE 的结果也要过同样的确定性校验；复核不可用 → 24 小时内后台重试 3 次，仍不行就永久停在“未计入”。这就是 StudyCoach `accepted=false` 边界（`SC/packages/ai-runtime/src/orchestrator.ts`）。
- **答案隔离**：标准答案只在 `server/core`；讲解与追问的备课包里永远不含 `answer_keys` 和标记为答案的资料（继承 DeepTutor 把答案放 sealed 的原则）。

### 5.4 取消 / 超时 / 兜底（决定）
- Coach 自己的首字截止 25 秒（桥内 Claude 出字前要等 90 秒才兜底，太久）：到点主动断开 → 桥杀掉 CLI 进程（改造记录：断开即杀、无残留）→ Coach 显式调兜底模型，消息头写「Claude 未响应，改由 GPT-6.1 Sol 回答（不流式，约 20–50 秒）」。
- 已出字后出错：状态 TRUNCATED，保留已有文字，给「重试」（用户发起的新任务，另计预算），不自动兜底（桥也只能截断）。
- 总时长：流 180 秒（同桥 `STREAM_TOTAL_TIMEOUT`）；JSON 任务 Claude 60 秒、Codex 150 秒。
- 取消：客户端 `turn.cancel` → 服务器取消协程 → 关 HTTP → 桥杀进程组；消息状态 CANCELLED，文字保留。
- Claude 安全拦截：桥已自动转 Codex。**缺口**：桥回包里的 `model` 字段写的是请求的模型，兜底时 Coach 无法知道实际出答者（`subscription_bridge.py` `stream_response(..., model, ...)`）。

### 5.5 复用订阅桥与何时替换（决定）
- **M0–M1**：把 `subscription_bridge.py` 复制进 Coach 仓库，**另起一份在 8791**（改造记录里本来就用 `BRIDGE_PORT=8791` 测新版），只在副本上加三处：回包加 `served_by`；接受 `X-Request-Id`；按请求设推理强度。DeepTutor 的 8790 与运行文件一个字不动。Coach 只用 chat/completions 的“流式无工具”和“JSON 非流式”两种调用，完全不碰工具调用协议。
- **替换触发**（任一）：要接 Grok 导师；要按调用控制提示缓存或推理强度；桥的 Python 依赖出问题；DeepTutor 退役。替换物 = Kotlin `CliProvider`（`claude -p --tools "" --strict-mcp-config --setting-sources "" --output-format stream-json --include-partial-messages`，进程组终止，按家并发信号量），**跑同一套 live 测试且首字差 ≤0.5 秒才切换**。

### 5.6 真实验证而非 mock 冒充（决定）
生产组合根里没有 Mock Provider；桥健康检查失败时界面显示「导师离线」而不是任何生成的占位文字；每条 AI 输出都存 `served_by` 与 call_id 并显示模型名；单元测试可以用**录制回放**（录自真实调用，带 call_id 与时间，命名为 replay），但验收只认 live 测试（§11.4）。

**建议**：Codex 生成的题由 Claude 复核，反之亦然；Grok 进来后可作为第三复核人。**开放**：桥默认 Claude 推理强度 low 只在 5 道题上验证过（改造记录 §十 第 3 条），批改是否要用默认强度，由 S6 对比两档后定。

---

## 6. 客户端-服务端协议

### 6.1 端点（决定，前缀 `/v1`，JSON，kotlinx.serialization，`ignoreUnknownKeys`）

| 端点 | 用途 |
|---|---|
| `GET /hello` | 协议版本区间、服务器时间、功能开关 |
| `POST /pair` | 配对码换设备令牌 |
| `GET /courses/{c}/events?after=&limit=500` | 账本增量（客户端本地跑投影） |
| `GET /courses/{c}/snapshot` | 新设备冷启动：投影快照 + lastSeq |
| `POST /commands` | 批量命令（离线发件箱也走这里） |
| `GET /threads/{t}/messages?after=` | 消息补齐 |
| `GET /attempts/{a}` | 判分详情（作答后才返回 rubric 与参考答案） |
| `GET /sources/{doc}/pages/{pdfPage}`、`GET /search?q=` | 原文页、检索 |
| `GET /math/{sha}.svg` | 公式 SVG（不可变，强缓存） |
| `GET /offline-pack?size=10` | 离线练习包（服务器在下载时写 question_posed，offlinePack=true） |
| `WS /ws` | 流式与推送 |

命令：`open_kp, next_step, request_mode, ask, cancel_turn, start_practice(kind=practice|closed_book), reveal_hint, submit_answer, start_review(size), snooze_review, start_mock, submit_mock, file_quick_ask, save_note, edit_journal, override_kp, set_exam, dispute_grade`。信封 `{commandId: ULID, deviceId, type, payload, clientTime}` → `{commandId, status: OK|DUPLICATE|REJECTED, eventSeqs[], result}`。

### 6.2 WS 帧（决定）
```jsonc
// 客户端 → 服务器
{"t":"hello","protocol":[1,1],"ledgerAfter":{"<course>":1834},"threads":{"<thread>":57}}
{"t":"cmd", ...同 /commands 的单条命令 }   {"t":"ping"}
// 服务器 → 客户端
{"t":"welcome","protocol":1,"serverTime":"…"}
{"t":"ledger","course":"…","events":[…]}                      // 账本增量，客户端按 seq 去重
{"t":"msg.start","thread":"…","msg":"…","mseq":58,"model":"claude-sonnet-5-5","mode":"intuition","refs":[…]}
{"t":"msg.math","msg":"…","sha":"…","tex":"…","display":false,"w":4.2,"h":2.1,"v":-0.6}  // 先于含闭合定界符的 delta
{"t":"msg.delta","msg":"…","i":17,"text":"…"}               // i = delta 序号
{"t":"msg.snapshot","msg":"…","i":40,"text":"…全文至此"}     // 断线重连时
{"t":"msg.end","msg":"…","status":"complete","servedBy":"gpt-6.1-sol","fallback":true,"citations":[…],"sha":"…"}
{"t":"card.question","attempt":"…","question":{/* 无答案 */},"purpose":"closed_book"}
{"t":"card.grade","attempt":"…","verdict":"pass","score":4,"max":5,"review":"pending|verified|uncertain"}
{"t":"progress","stage":"preparing|grading|reviewing","label":"正在核对教材第 8 页"}
{"t":"error","code":"tutor_unavailable","retryable":true,"detail":"…"}
```

### 6.3 断点续传与离线幂等（决定）
- 两种序号：课程账本 `seq`（服务器分配、连续）；线程消息 `mseq` + 流内 delta 序号 `i`。服务器把进行中的消息全文每秒落库一次；重连时对仍在流的消息先发 `msg.snapshot` 再接实时 delta，客户端按 `(msg, i)` 去重。账本落后 >1000 条直接拉快照。（DeepTutor 的 `resume_from`/`after_seq` 思路，`DT/api/routers/unified_ws.py`。）
- 离线发件箱：命令 ID 在设备上生成；服务器 `command_log(device_id, command_id)` 去重，重复返回原结果；派生事件的幂等键为 `cmd:{device}:{command}:{n}`。`submit_answer` 另有自然键 `attempt:{id}:answer`，跨设备也只能一次。离线作答的 `occurredAt` 用设备时间（日记算到真实学习的那天），但不可能成为 TIMED。

### 6.4 版本协商（决定）
`hello` 交换 `[min,max]`，取最高公共主版本；无公共版本 → 客户端显示「请更新」并给 `/app.apk`（沿用现在手机入口的做法）。次版本只加字段；事件 schema 按类型独立演进（§3.4）。

### 6.5 认证（决定）
服务器只绑定 `127.0.0.1` 和本机 Tailscale 地址（现为 <Mac 的 Tailscale IP>），不绑 0.0.0.0。Tailscale（WireGuard）已经负责加密与设备准入；应用层再加一层**设备令牌**：Mac 桌面端显示 6 位配对码（5 分钟有效）→ 手机输入 → 服务器发 256 位随机令牌、只存 sha256；每台设备可单独吊销。安卓令牌存 Keystore 加密存储；Mac 桌面端同机，令牌文件 0600 自动配置。安卓 `network_security_config` 只对该地址允许明文。**建议**：开启 tailnet HTTPS 后用 `tailscale serve` 提供 HTTPS（要改 tailnet 设置，由他定）。**开放**：用 Tailscale LocalAPI 的 whois 识别对端设备、免配对——更省事，但多一层依赖，CLI 目前不在 PATH。

---

## 7. 客户端架构

### 7.1 Gradle 布局（决定）
```
coach/  settings.gradle.kts  gradle/libs.versions.toml  gradlew
  build-logic/        约定插件：kmp-lib、compose-app、jvm-server（统一 JVM 17 目标）
  shared/{domain,protocol,client,ui}
  app-android/  app-desktop/
  server/{app,core,store,content,ai}
  tools/{migrate-deeptutor, bridge(py 副本), mathsvc(node + mathjax-full)}
  acceptance/         验收脚本、语料、录屏（只有验收能改）
```

### 7.2 依赖选型（决定；版本以 spike 当天最新稳定版写进 `libs.versions.toml`）

| 用途 | 选 | 理由 | 备选 |
|---|---|---|---|
| 语言/UI | Kotlin 2.x（K2）+ Compose Multiplatform | 已定 | — |
| 导航 | JetBrains `navigation-compose`（类型安全路由） | 官方 KMP、安卓返回手势 | Decompose（更灵活但更重） |
| 面板布局 | 自写断点 + 面板（§8） | 我们的断点是按实测 dp 定制的，官方 `material3-adaptive` 的默认断点不够细 [其 KMP 成熟度未证实] | ListDetailPaneScaffold |
| 状态 | androidx lifecycle ViewModel（KMP）+ StateFlow，每屏 `State`/`Intent` | 少依赖、可测 | MVIKotlin、Orbit |
| DI | 手写 `AppGraph` | 应用小，编译期安全 | Koin |
| 网络 | Ktor client（安卓 OkHttp 引擎、桌面 Java 引擎）+ WebSockets | 与服务端同栈 | — |
| 本地库 | SQLDelight 2（安卓驱动 / JVM 驱动），服务端同用 | 两端同一种 SQL；FTS5 写法 [未证实]，不行就原生 SQL | Room KMP |
| Markdown | `org.jetbrains:markdown` 解析 AST + 自写渲染器 | 需要精确控制“只重解析尾块”，第三方渲染器的流式增量 [未证实] | mikepenz multiplatform-markdown-renderer |
| 公式 | 服务端 mathsvc（Node 22 + mathjax-full，常驻进程，JSON 行协议）→ SVG + 尺寸；客户端 Coil 3 + coil-svg | 见赌注 2 | 安卓块级 WebView + 本地 KaTeX |
| 折叠姿态 | Jetpack WindowManager（`expect/actual`，桌面恒为 FLAT） | 官方 | — |
| 测试 | kotlin-test、kotest-property、Turbine、Compose UI test、Roborazzi（截图，桌面支持 [未证实]） | 属性测试放 commonTest，两端共用 | Paparazzi |

### 7.3 状态与数据流（决定）
`SyncEngine`（进程级，不随界面重建）持有 WS、发件箱、本地账本副本；投影由 `shared/domain` 在客户端本地算，界面只观察 SQLDelight 的 Flow。写 = 命令进发件箱 → 服务器 → 事件回流 → 投影更新；未确认命令作“待定覆盖层”显示（如「待批改」）。规则只写一次，离线也准确。
导航：顶层 今天/路线/复习/记录/资料；「学习/{kpId}」是详情目的地，宽屏时落在详情面板。深链 `coach://kp/{id}`。

### 7.4 流式 Markdown + LaTeX（决定 + 备用方案）
- **服务端文本管线**：规范化定界符（`\(\)`、`\[\]`、`$`、`$$`）与裸公式（移植 `SC/packages/domain/src/math-markdown-policy.ts`、`bare-equation-policy.ts`）；在流中识别闭合的公式片段 → mathsvc 渲染（几毫秒 [估算]）→ 先发 `msg.math`（tex、sha、宽高与垂直偏移，单位 ex）再放行含闭合符的 delta。SVG 用 `currentColor`，客户端按主题着色。
- **客户端**：AST → 块列表；已完成的块不可变、带稳定 key，Compose 跳过重组；delta 每 50ms 合帧一次，只重解析最后一个块。行内公式用 `InlineTextContent` 占位（尺寸已知），块公式单独成块、超宽可横滑；表格、代码块横滑；`SelectionContainer` 支持复制。
- **[未证实]** Compose 行内占位只能按 TextCenter/AboveBaseline 等对齐，不能按任意基线偏移；对策：mathsvc 把 SVG 视框上下补齐，使数学轴居中。S2 验证。
- **滚动**：不用 reverseLayout；距底 48dp 内视为“钉底”，每帧滚到末尾；用户上滑即解除钉底并显示「新内容 ↓」；窗口变化时按（首个可见消息 id, 偏移比例）恢复。
- **备用**：S2 在安卓失败 → 仅公式块/表格块用 WebView + 打包的 KaTeX，高度经 JS 回调；桌面保持原生。再失败才考虑整段 WebView。

### 7.5 离线缓存（决定）
SQLDelight：账本副本、投影缓存、全部线程消息（文本很小）、公式 SVG（LRU 20MB）、离线练习包、发件箱、输入草稿（每次修改 300ms 防抖落库，进程被杀也不丢）。

### 7.6 本机工具链现状与缺口（本机实测）

| 项 | 现状 | 缺口 / 动作 |
|---|---|---|
| JDK | Homebrew OpenJDK 17.0.20（arm64） | 够 Gradle/AGP；桌面打包用 JDK 自带 jpackage |
| Gradle/Kotlin | 未装 | 用 `gradlew`；首次要联网下 Gradle 与依赖（约 1–2GB [估算]）；Multica 单命令 600 秒上限，首次同步用 `screen -dmS` 跑 |
| Android SDK | `/opt/homebrew/share/android-commandlinetools`：platforms 34/35、build-tools 34/35、system-image android-35；sdkmanager 在 PATH | adb/emulator 不在 PATH → 设 `ANDROID_HOME`；新 AndroidX 可能要 compileSdk 36 [未证实] → 用 sdkmanager 补 |
| AVD | `sable-api35`（1080×2400@420，普通手机） | 用 `adb shell wm size 1248x1972 / 1848x2448 / 2448x1848` + density 420 模拟四形态（改造记录 §八 用这些尺寸验证过内屏）；真机走无线调试（要他在手机上开开发者选项） |
| Node | `~/.nvm` v22.23.1 | mathsvc 需 `npm i mathjax-full`（联网一次） |
| IDE | Xcode 有；无 Android Studio / IntelliJ | 不需要，agent 全用命令行 |
| 机器 | 18 核 / 48GB | `org.gradle.workers.max=8`，大构建避开学习时段 |
| Tailscale | Tailscale.app 有，CLI 不在 PATH | 服务端绑定地址写进配置 |
| 现有安卓壳 | `~DT/android/build.sh` 用 aapt2+javac+d8，无 Gradle | 新项目不复用其构建方式；新包名（如 `local.coach`）与 DeepTutor 壳并存，用同一把 `~/.android/debug.keystore` 签名 |

**建议**：minSdk 26（与现壳一致）、targetSdk 35。**开放**：Compose Desktop 在 macOS 27 beta 上的渲染与输入法是否有坑（本机是 beta 系统 [未证实]），S1 顺带看。

---

## 8. 自适应与折叠屏

### 8.1 断点（决定；按**窗口**尺寸算，不按屏幕，兼容三星分屏）

| 形态（估算 dp） | 类别 | 布局规则 |
|---|---|---|
| Fold8 外屏竖 475×~750 | Compact | 底栏 5 项；学习页全屏对话，KP 头收成一行；原文用底部抽屉；题卡整宽；选项单列 |
| Fold8 外屏横 751×~450 | 短高 | **高 <480 优先于宽度规则**：左侧窄图标栏（无文字）；隐藏头部，只留浮动 KP 名条；不分栏；键盘弹出时选项改两列 |
| Fold8 内屏竖 704×~930 | Medium | 导航栏（rail）；单内容栏（阅读宽上限 680）+ 右侧可滑出 360dp 覆盖式辅助栏（原文/大纲） |
| Fold8 内屏横 933×~700；Pad Mini 竖（若 ≥840） | Expanded | rail + 双栏：对话（弹性，上限 720）+ 辅助栏 360（原文页、本 KP 作答历史）；路线：列表 360 + 详情 |
| Pad Mini 竖（若 <840） | Medium | 同内屏竖 |
| Pad Mini 横 1120–1260、Mac 窗口 ≥1200 | Large | rail + 大纲 300 + 对话（上限 760）+ 原文 380；侧栏可收起 |

双栏条件 = 宽 ≥840，或（宽 ≥700 且高 ≥600 且处于书本姿态）。字体：正文阅读 16sp/行高 1.6；Compact 下 display 字号封顶 30。

### 8.2 折叠姿态（决定）
`FoldingFeature` 为 HALF_OPENED 且铰链水平（桌面姿态）→ 上半对话、下半输入栏与题目选项；铰链垂直（书本姿态）→ 面板分割线对齐铰链，文字绝不跨铰链；FLAT 忽略。

### 8.3 状态保持（决定）
Activity 自行处理 `screenSize|smallestScreenSize|screenLayout|orientation|density` 配置变化，不重建；状态在 ViewModel + 进程级 SyncEngine；草稿落库；列表按消息 id 锚定；WS 不在界面层，所以折叠/展开时流式不中断。App 进后台时服务器照常把这轮写完，回前台按 `msg.snapshot` 补上。

### 8.4 键盘 / 输入法与流式输入（决定）
全屏沉浸 + `imePadding()`（沿用壳 v1.4 的“键盘只让出键盘高度”，改造记录 §八）；输入栏最多 6 行再内部滚动。手机上回车换行；硬件键盘/桌面回车发送、Shift+回车换行；**输入法组字期间回车永不发送**（中文输入，桌面端 Compose 的组字处理 [未证实]，S1 测）。流式进行中输入框仍可编辑，发送 = 排到本轮结束；长按发送 = 「打断并发送」。键盘开着折叠/展开，焦点与光标保留（`TextFieldValue` 在 ViewModel）。

**建议**：S1 第一件事是在 Fold8 与 Pad Mini 真机上打印实际窗口 dp，用实测值替换上表的估算（dp 受系统显示缩放影响，改造记录 §八）。**开放**：三星外屏横屏他是否真的会用；若不用，「短高」规则可降级为“能用即可”。

---

## 9. 设计系统

### 9.1 令牌 → Compose 主题（决定）
来源 `SC/packages/design-tokens/tokens.css`。只把**语义层**（第 2 层）做成 `CoachColors`（`bgCanvas/bgCanvasDeep/surface1/surface2/surfaceSolid/inverse/fgPrimary/fgSecondary/fgTertiary/onAccent/borderSubtle/borderStrong/focusRing/accent/accentDeep/accentSoft/mint*/status{success,warning,danger,info,accent,neutral}{Fg,Bg}/actionPrimary(+Hover)/actionDanger`），色板（第 1b 层）是主题文件里的 private 常量，组件不能直接引用——保留它“三层”的好处，不保留它的脚本门禁。同时映射到 Material3 `ColorScheme`（primary = actionPrimary `#bd4070`/暗色 `#f088b0`，background = `#f4efe6`/`#191712`，surface = `#fffaf2`/`#26221c`），让 M3 组件默认就对。

**对比度（我实测 WCAG）**：fgPrimary/纸色 13.4、fgSecondary 4.99、白字/主按钮 5.08、三种状态色对 5.2–5.4，均达标；**fgTertiary `#96998f` 在纸色上只有 2.53、accent `#ec78a4` 2.35、accentDeep 3.84、暗色 fgTertiary 4.14**。决定：tertiary 与 accent 不得用于承载信息的文字（只用于禁用态、装饰、填充、图标 ≥3:1 的场合）；暗色 tertiary 调到 `#8f8a80`（对 surfaceSolid 4.6）。对比度检查写成 commonTest 单元测试——它测的是“看不看得清”，属于用户可感知质量。

- **字体**：Mac 端用系统 Iowan Old Style + Songti SC（与 StudyCoach 一致）。安卓没有宋体，Iowan 不能打包：标题用打包的 Noto Serif SC 子集（GB2312 常用字 + 拉丁，1–2 个字重，约 3–5MB [估算]）+ 开源拉丁衬线（Source Serif 4）；正文用系统无衬线（三星/Noto Sans CJK），清晰且不增包。理由：知识点名是长中文，标题必须覆盖 CJK；正文用系统字体最稳。
- **字号/间距/圆角**：px→sp/dp 1:1（12/13/14/16/19/24/30/38；4dp 基准间距；圆角 12/18/28/36/全圆）。
- **动效**：120/200/320ms，`CubicBezierEasing(0.2,0,0,1)` 与 `(0.3,0,0,1)`；系统“减少动态效果”（安卓动画倍率 0、macOS Reduce motion）→ 时长归零；流式文字不做逐字淡入（性能）。
- **毛玻璃**：v1 不做模糊，用不透明 surface（安卓 12 以下无 RenderEffect，且流式时模糊很贵）。
- **暗色**：跟随系统，设置页可固定。

### 9.2 组件与状态（决定）

| 组件 | 状态 |
|---|---|
| 主/次/幽灵按钮 | 默认、按下、禁用、加载 |
| 今天主卡 / 复习卡 / 日记卡 | 正常、空、离线（灰显+最后同步时间） |
| KP 行 | 未学、学习中、已通过、稳固、曾稳固（暂停）、需回炉、前置锁、手动标记、迁移而来 |
| 模式药丸 | 未选、选中、建议（带点）、禁用 |
| 导师消息 | 流式中、完成、截断、已取消、失败、兜底（显示真实模型）、引用芯片 |
| 选择题卡 | 未答、已选、提交中、离线待批、对、错、已作废 |
| 构答题卡 | 编辑、提交中、批改中、复核中、已核实、未确认（不计入）、申诉中 |
| 批改卡 | rubric 逐条 + 学生原文高亮（逐字证据）+ 批改/复核模型 |
| 原文页查看器 | 加载、高亮引文、页未找到 |
| 输入栏 | 空闲、流式中（可编辑）、离线、组字中 |
| 横幅 | 离线、Mac 未连接、导师降级、“现在问算提示”、版本过旧 |
| 其他 | 空状态、错误态（重试）、骨架屏、撤销 Snackbar、底部抽屉、rail/底栏、模考计时器 |

**建议**：安卓标题字体给他看两张截图二选一（打包宋体子集 vs 全无衬线），这是品味选择。**开放**：Noto Serif SC 子集的实际体积与常用字覆盖率，S1 量。

---

## 10. DeepTutor → Coach 迁移（决定；工具 `tools/migrate-deeptutor`，只读源库，可重复运行）

| 来源（本机实测） | 去向 | 规则 |
|---|---|---|
| 课程 `course_8f01bd033655`（`~DT/data/user/workspace/courses/courses.json`） | Course | code、title、instructions 原文；examAt = 2026-11-02 14:25 Europe/London；题型结构（MCQ/Essay/Analytical）写进 instructions |
| 课程库 `~DT/library/ECNM10119-健康经济学/`：教材按章 md + 页标记、笔记、资料、原件 | source_docs/pages + FTS；原件 PDF 进 blobs | 按 `<!-- PDF 第 N 页 · 书第 M 页 -->` 切页；无页标记的按标题切块；路径含「答案」或已知答案资料的文档标 `tutor_visible=0`；**sealed 一律不导入**（v1） |
| 掌握路径 `topic_706ba859…`（`~DT/data/user/workspace/learning/mastery/mastery.sqlite3`：12 模块、49 KP；现类型 memory 26 / procedure 23） | outline_revised（新 ULID，`legacy_id` 存原 id） | 原类型取 `gate_types_before.json`（relax 前的 concept/design）；名称带【Essay】→ examFormats 含 ESSAY；9 个 KP 的 `lesson_evidence` 来源 → anchors；前置依赖 = 空，另出“模型提议 → 他确认”步骤 |
| 另一路径 `topic_dd61…`「期中冲刺：Ch1–12」（62 KP、0 作答） | 不导入，原 JSON 存档 | **开放**：他确认不用 |
| 17 条作答/证据（5 quiz + 12 review）、5 个 `mastery_levels=1.0` | legacy_attempt | 计入「已通过」公式（保证这 5 个 KP 迁移后仍是已通过，学习不中断），**永不计入稳固**（StudyCoach legacy_system_verified 原则，`SC/docs/architecture/mastery-evidence-classification.md`） |
| 5 条 `repetition_states` | legacy_schedule | dueAt = `next_review_at`；interval = next − last |
| `mastery_events` 186 条（含 25 次 overridden / 25 次 cleared，净覆盖为 0） | 不重放，整表 JSON 存 blob，sha 写进 legacy_imported | — |
| 108 题（`~DT/data/digests/questions/ECNM10119-期中练习题.json`：单选 60、简答 48；字段 question/options/correct_answer/explanation/difficulty/tags） | questions + answer_keys + question_kp | 选择题：键 = 选项字母；简答：参考答案 → 模型生成 rubric（条目+分值+依据锚点）→ 异厂复核 → 他抽查 10 题；**tags 只有章（Ch1、Week 1），没有 KP** → 用 KP 名的【W1·Ch1–2】前缀把候选缩到本章，再由模型映射到 1–2 个 KP、复核；`chat_history.db` 里 20 条 `assessment_attempts` 若对应题库题，该题标为已见 |
| 笔记本「章节精要」（`notebook/c1388fac.json`，12 条，按章） | note_saved，挂在引用该章的模块下各 KP | 原文 Markdown 不改 |
| 阅读集 13 份、复习书 6 章（`workspace/reading/*`） | source_docs（kind=reading/review_book） | — |
| 学习日志（`~DT/data/learning_journal/journal.json`） | JournalDay 迁移区 | 按日期归档，标「DeepTutor 日志」 |
| 会话（`chat_history.db`：1 个会话、86 条消息） | 只读存档线程，挂课程 | 不参与任何投影 |
| 导师规则（`library/…/AGENTS.md`）、人格 `health-econ-tutor` | 讲解系统提示的种子 | 优先课程资源、按页引用 |

幂等：`legacy_imported.idemKey = legacy:{源文件 sha256}`，重跑无副作用；提供 `--dry-run` 打印计数与差异。迁移后自动对账：49 KP、108 题（60/48）、17 条作答、5 条复习、12 条笔记；5 个已通过 KP 在路线上显示为已通过且标「迁移而来」。读源库一律用只读方式（SQLite `?immutable=1`），DeepTutor 照常运行。

**建议**：期中后（11-03 起）再做正式迁移，期间 DeepTutor 新产生的作答由第二次增量运行补进（按源事件 sha 去重）。**开放**：「资料」「答案」目录里哪些可给导师看，需要他逐个确认。

---

## 11. 测试与验证

### 11.1 领域属性测试（决定，kotest-property，commonTest，两端共用）
- 重放确定性：任意切分批次 / 快照+尾部 / 全量，投影字节一致。
- 幂等：重复幂等键的事件不改变任何投影。
- **稳固 ⇒ 已通过**；手动标记永不产生稳固；作废一个作答 ≡ 该作答从未发生（除审计字段）。
- 追问、复习结果永不改变「已通过」；随口问不产生任何掌握证据。
- 同题重试永不合格；单选题非限时/模考永不合格；AI 未复核的批改不进入任何一层。
- 首做轨一旦写入不被重做覆盖（`dualTrackColumnsAreIndependent` 的 Kotlin 版）。
- 考试在未来时，任何已通过 KP 的到期日 ≤ 考前一天。
- 投影不依赖当前时间（除“今天到期”过滤）。

### 11.2 黄金用例（决定，JSON 事件脚本 + 期望投影）
3 连对 → 已通过；2 连对 → 0.8 不过；错对对对 → 0.8 不过、再对两次 → 过；提示作答按半分；闭卷简答 0.85（第 1 天）+ 限时单选（第 2 天）→ 稳固；同日两条 → 不稳固；hints=3 → too_many_hints；重复题 → repeated；复习 FAIL → 需回炉且已通过不变；迁移 5 个 KP → 已通过非稳固；作答作废；考前压缩；逐字证据（中、英、边界、太短）；rubric 被篡改。

### 11.3 UI：测“他感受到的质量”（决定；数字只报告不设卡，失败当 bug）
1. 冷启动 → 今天 → 继续 → 首字出现：点按数与时间（live，真桥）。
2. 流式稳定：回放 5 段真实长回答（含 10+ 公式）——钉底时不跳；上翻后首个可见消息位移 ≤1dp；最终文本节点里没有 `$`、`\frac` 泄漏。
3. 公式正确：课程语料 50 条公式截图，验收逐条人工看一次，之后截图比对防回归。
4. 四形态切换：模拟器上在流式中、打字中、选了选项未提交时轮换 `wm size` → 草稿、选项、流、滚动锚点全部保持。
5. 离线：停服务器 → 1 秒内显示缓存今天与横幅；做离线包；恢复后发件箱排空、判分出现、无重复。
6. 掉帧：Fold8 真机流式时 JankStats 报告掉帧率。
7. 对比度单元测试（§9.1）。

### 11.4 AI 链路真实端到端（决定；`./gradlew liveTest`，不进默认构建，验收必跑）
讲一个 KP：首字时间、引用全部合法且 ≥1；出题+复核：故意注入 5 道错键题，复核须拦下；批改：20 道他（或我）人工评分的简答，AI+复核与人工差 ≤1 分的比例；取消：流到一半取消，2 秒内 `pgrep -f "claude -p"` 回到基线；兜底：把主模型配成不可用，回答由兜底模型给出且界面显示其名字；每次运行保存 call_id 清单，验收者可复跑。

**建议**：live 测试只在他不学习的时段跑，并计入后台调用额度。**开放**：20 道简答的人工基准分由谁打（他本人最准，但耗时）。

---

## 12. 里程碑、spike、风险、不做清单、团队

### 12.1 Spike 顺序与判定（决定；11-02 前只在非学习时段跑重构建）

| 序 | Spike | 负责 | 通过 | 失败时 |
|---|---|---|---|---|
| S2（第 1 天起，最先） | 流式 Markdown + 公式 | 前端/安卓 + 工程（mathsvc） | §11.3 第 2、3 条全过；Fold8 掉帧 <5% | 安卓切块级 WebView+KaTeX；再不行整段 WebView |
| S1 | 工具链 + 两端空壳 + 窗口/姿态读数 + 中文组字回车 | 前端/安卓 | 一个仓库出 APK 和 Mac 应用；四形态 dp 读数正确 | 换依赖版本组合 |
| S3 | Ktor → 桥 8791 真流式、取消、兜底、WS 续传 | 工程主力 | ≤40K 字首字 p50 ≤5 秒；取消 2 秒内无残留进程；断网 10 秒重连文本 sha 一致；兜底显示真名 | 查桥日志；必要时提前做 CliProvider |
| S4 | 账本 + 投影 + 策略 | 架构师写规格，工程实现 | §11.1、§11.2 全过；5 万合成事件冷重放耗时（只报告。StudyCoach TS 版 5000 事件 p95 曾达 9.6 秒、后优化到约 0.38 秒，`SC/docs/architecture/adr-0001-single-projection-surface.md`，所以要量） | — |
| S5 | FTS5 + 中文 trigram + 页锚点 | 工程 | 「逆向选择」「adverse selection」「Grossman」都返回正确页 | unicode61 + 自建二元分词 |
| S6 | 简答批改质量 | 验收 | §11.4 批改一项出报告；复核拦下全部 5 处篡改 | 调提示词，或主批改换 Codex |

### 12.2 里程碑（建议，粗估，S 结果出来再收敛）
- **M0（约 1 周）**：6 个 spike、本文档转 ADR（只写真正分叉的决定）、领域规格、主题与组件初稿、迁移干跑。
- **M1（3–4 周）**：ECNM10119 闭环（今天/路线/学习/复习/记录/资料、两层掌握、统一复习、日记、随口问沉淀、迁移、配对、离线包），安卓 + Mac。验收 = 立项方案 §八：连续 7 天只用 Coach、≤3 次点按、首字 ≤5 秒、每个掌握变化可下钻到原题原答与判分依据。
- **M2（3–4 周）**：多课程导入（PDF/PPTX/MD）、模考与修复双轨界面、深查原文代理、Grok 导师、前置依赖编辑器。
- **M3（2–3 周）**：Windows、到期复习本地通知（WorkManager，用已缓存的到期日，离线也能提醒）、无障碍、打磨。

### 12.3 第一版不做
课程导入界面（只有迁移工具）；PDF/PPTX 解析与页图；全局阶段状态机；课程版本不可变机制；图片/手写作答；导师工具调用与自由代理；多用户、云同步、VPS；Windows、iOS；通知与小组件；伙伴、写作、看板、深度研究等 DeepTutor 功能；调度参数自动优化；任何“行数/门禁数”类治理。

### 12.4 风险（决定的对策）

| # | 风险 | 对策 |
|---|---|---|
| R1 | 公式/流式渲染 | S2 最先做，有两级备用 |
| R2 | Mac 在线率 11–38% | 离线读 + 离线练习包；**建议（他定）**：服务器仅在接电源时持有 `caffeinate -s` 防休眠断言（不改系统设置），或日后搬到常驻机器（要把订阅登录放过去）；服务端设计成 `~/Coach/` 一个目录 + 一个 jar，搬迁只是拷贝 |
| R3 | 订阅 CLI 掉登录、限流、条款 | 兜底链 + 界面说真话；Antigravity 默认关 |
| R4 | 简答批改质量 | 固定 rubric + 逐字证据 + 异厂复核 + 不确定不写；S6 量化 |
| R5 | 工作量（月级）与范围蔓延 | M1 只做闭环；不做清单写死；每张卡交付首行写“达到/部分/未达到 + 数字” |
| R6 | 迁移 ID 漂移（DeepTutor 改名会重生 ID，改造记录 §七） | 迁移时一次性映射到 Coach ULID，之后改名不改 ID |
| R7 | 与 DeepTutor 抢 CPU/额度 | 独立桥 8791；后台每日调用上限；大构建避开学习时段 |

### 12.5 拆给 agent 团队（建议，按目录所有权避免踩踏）

| 岗位 | 拥有（只有他能合入） | 不碰 | 产出 |
|---|---|---|---|
| 架构师（Claude） | `shared/domain` 的公开 API（事件、策略）、`shared/protocol`、ADR、黄金用例的期望值 | 实现细节 | 规格、契约变更评审 |
| 工程主力（Codex gpt-6.1-sol；第二 CB-Flash2） | `server/*`、`tools/*`、domain 的实现 | `shared/ui`、`app-*` | 服务端、AI runtime、迁移、mathsvc |
| 前端/安卓（Builder-C；Draft 出界面初稿） | `shared/ui`、`shared/client`、`app-*` | `server/*`、domain 规则 | 主题、组件、屏幕、渲染、自适应 |
| 验收（QA/QA-C，关键项 cross_review） | `acceptance/` | 产品代码 | live 测试、语料、录屏、复跑结论 |

- **契约先行**：`shared/protocol` 与事件 schema 每个里程碑冻结一次，改动走单独小 PR、架构师过目。前端可用**标明为开发用**的契约假服务器并行，但验收只认真服务器 + 真桥。
- 每张卡写清会改的目录；同一模块同时只开一张执行卡；每人 ≤2 张（Multica 规则）。
- 一台机器：共享 Gradle 缓存，首次同步后 `--offline`；模拟器同一时间只给一张卡；超 10 分钟的任务用 `screen -dmS`。
- 验收者自己复跑，不认“测试通过”字样（立项方案 §五）。

**开放问题汇总**：① 另一条掌握路径是否丢弃；② 是否允许服务器防休眠或搬迁；③ HTTPS 是否开启；④ 108 题 rubric 抽查由谁做；⑤ 12 月考试的范围与日期（决定考前压缩何时再次生效）。
