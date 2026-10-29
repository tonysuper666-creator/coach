# Coach 完整设计（总设计师：Sonnet 5.5，第 1 轮）

标记：**决定**＝我押注、可直接开工；**建议**＝待团队裁决；**开放**＝要用户或 spike 回答；[未证实]＝无源码或实机证据。路径缩写：`SC/`＝StudyCoach 只读源码（scratchpad/sc/src），`DT/`＝`~/DeepTutor/.venv/lib/python3.12/site-packages/deeptutor`，`~/DT/`＝`~/DeepTutor/`，`改造记录`＝`~/DT/DeepTutor改造记录.md`，`对比方案`＝`~/DT/StudyCoach与DeepTutor深度对比与结合方案.md`。

## 0 核心赌注

1. **流程控制权放服务端代码，模型只填槽位；不用 tool-calling 提示协议。** 证据：`DT/capabilities/mastery/loop.py` 前 180 行是一批正则（`_QUESTION_PROMISE_RE`、`_CARD_DELIVERY_RE` 等），全在补“模型该调 mastery_quiz 却没调”；改造记录§三记每轮开头有约 11 万字、14–24 秒的隐形工具调用。代价是失去模型临场调度，用“随口问”补偿。
2. **证据类别在写入账本时钉死，“已通过”与“稳固”是同一本账上的两个纯谓词。** 规则会改，账不能改。
3. **公式走“服务端 MathJax→SVG→客户端矢量绘制”，流式 Markdown 走自写增量分块器**，不在 Compose 里嵌 WebView/JCEF：三端一份实现、暗色不重渲染、可内容寻址缓存。这条最可能失败，故为 S1 spike，退路已定（§7）。

**致命问题声明**：没有发现要推翻已定决定的问题。但用户须知：(a) M1 赶不上 11-02 期中，期中前继续用 DeepTutor，故迁移器必须可重复运行（§10）；(b) Mac 在线率 11–38%（`~/DT/新应用立项方案v2.md` §七.4）使“随时能学”取决于运维，我只用离线包缓解，不承诺。

## 1 产品与学习闭环

| 场景 | 设计 |
|---|---|
| 每天 | 打开→今天→唯一主按钮→学习或复习，≤3 次点按；按钮文案写明要做什么，目标由 `nextAction()` 定（§2） |
| 考前冲刺 | 设了 Exam 后今天页显示倒计时，复习间隔被考试日截断；不新增模式、不改证据规则 |
| 手机碎片 | 外屏竖单手：今天→复习→逐题卡；退出不丢位置（位置是投影） |
| 折叠/展开 | 状态在 ViewModel 与账本，布局只是它的另一种视图（§8） |
| 离线/Mac 休眠 | 离线包：在线时预取今日复习项、当前 KP 已讲内容、预生成题、公式 SVG；离线可复习、可答已下发的题（作答排队）；不可新讲与随口问，明说“导师离线” |

| 屏 | 主动作 | 空状态 | 错误态 |
|---|---|---|---|
| 今天 | 继续；次卡：到期复习 N、昨日日记、随口问 | 无课程→导入/迁移；无待办→去复习 | 不可达→“导师离线”横幅，复习与已下发题可用 |
| 路线 | 点 KP 进学习 | 无大纲→导入 | 失败→本地缓存并标时间 |
| 学习 | 讲→练→批→下一步 | 新 KP 自动开讲 | AI 失败保留进度，“重试/换导师”；流中断“继续写完” |
| 复习 | 开始（先看只读队列） | “今天没有到期项”＋下次到期时间 | 同上 |
| 记录 | 日记＋证据下钻 | “今天还没有记录” | 摘要失败→只显示事实区 |
| 资料 | 读教材页（书页/PDF 页）、笔记、搜索 | 导入 | 页缺失→显示引用原文 |

**对话模型（决定）**
- 对话＝Thread，绑定 `Subject`＝`kp | review_item | course`。界面没有“新建对话”和笔记本/阅读集/题库选择器；Thread 由服务端在首次进入对象时创建。
- 轮次四种：`teach`（状态机驱动，可出题、可产生证据）、`ask`（随口问/追问：只读，无权改掌握度、无权出题）、`feedback`、`review`。同 Thread 混排，但 ask 画成缩进的“追问”气泡，与主线分开——针对 DeepTutor 追问被教学流程打断（对比方案§三）。
- ask 上下文＝当前 Subject 最近 12 个 ask 轮＋该 KP 最近一次讲解摘要＋检索片段（沿用 SC）；发现别的 KP 更相关只给“转到 X”按钮，不自动改绑。首页“随口问”Subject＝course：服务端找最相关 KP 并**在回答头部明写**，一键可绑定。
- 多导师是路由属性不是资源：每类任务有默认链（§5），Turn 记录 `provider`，气泡带小标；“问另一位导师”＝同 Thread 新增指定 provider 的 ask 轮。
- 沉淀：ask 回答下两个 chip——“存为笔记”（挂 KP，进资料与日记）、“加入复习”（仅练习级）。同一 KP 一天追问≥3 次，日记提示“建议做短诊断”，并给下次备课上下文加一行。


## 2 学习规则

**两层共存（决定）**

| 层 | 定义 | 作用 |
|---|---|---|
| 已通过 `passed` | 该 KP 的“有效首答”（每题只计第一次独立作答；key 已复核；提示<3；未作废）中，**最近 3 次全对且 3 题指纹互异**；或任一简答/计算首答经独立复核≥0.8；或导入的 legacy 通过 | 解锁下一步、进复习队列；冲刺口径 |
| 稳固 `solid` | ≥2 条 `current_objective` 证据（`SC/packages/domain/src/mastery-evidence-policy.ts` 全部条件）、平均强度≥0.85、跨≥2 个自然日 | 展示“真掌握”；不影响导航与调度 |
| 自称已会 `claimed` | 用户显式标记 | 等同 passed 用于导航，徽章单列，永不计入 solid（对应 `DT/learning/policy.py` 的 learner override） |
| 不稳 `shaky` | 最近 2 次复习均非 pass | 仅标记，不降级 |

理由：
- 3 连对：`DT/learning/mastery.py` 取最近≤5 次加权并设 `_CONFIDENCE_CAP`，门槛 0.9 实际＝“窗口内无错且至少 3 次”。Coach 把窗口收成最近 3 次：能向用户直说，对早期错误更宽容；DeepTutor 已过的点必满足它，迁移不掉级。5 选 1 连猜中 3 题≈0.8%。
- solid 复用 SC 的 fail-closed 门：purpose∈{closed_book_derivation, unseen_variant, timed, mock}、首次见题、attempt==1、≥0.8、hints<3。选择题**不被排除**：首次见到且距讲解≥一个会话的新题可标 `UNSEEN_VARIANT`，MCQ 也能累积 solid，只是慢；刚讲完就做的题＝`PRACTICE`，只计 passed。冲刺与严格是同一条证据的两个读数。
- **题量约束**：108 题/49 KP≈2.2 题/KP，选择题 60 道≈1.2/KP（`~/DT/data/user/chat_history.db`），且这 108 题本身由模型生成（`practice_questions.py`，改造记录§五），key 未经独立复核。所以 passed 必须靠生成题；题库题与生成题一律过“盲复核”（§5）才 `accepted`，否则只展示、不入证据。

**前置依赖（决定）**：导航失败放行，证据失败关闭。`nextKp()` 跳过前置未满足的 KP；任何 KP 都能手动打开并提示“前置未过：X”；剩余全被锁时忽略前置。理由：主路径 49 个 KP 的 `prerequisite_ids` 全空；62-KP 路径的 61 条边出自模型且 0 作答（`mastery.sqlite3` 实测），只作候选；M1 只有模块级顺序＋人工确认的边。

**阶段（决定）**：M1 不实现。SC 阶段机（`SC/packages/domain/src/learning-phase.ts`）只为让“通读期的题不算掌握”，`purpose+novelty` 已够。模考首做/修复双轨（`SC/.../score-track.ts`）原样留到 M2，只用于模考卷。

**复习队列合一（决定）**：复习只改日程、不改 passed/solid（SC 规则）。一种 `ReviewItem(subjectKind=kp|question, subjectId)`，入队：KP 首次 passed/claimed；题目首答错。日程沿用 `SC/packages/projections/src/review-queue.ts`：首间隔 `strength≥0.85→7，≥0.6→3，否则 1`（claimed＝1）；pass→×ease（2.3 起，每次+0.1，上限 3）；needs_work→×0.5；fail→1 天且 ease−0.2（下限 1.3）。题目项连续 2 次 pass 且间隔≥3 天后 graduated；同 KP 的题目项折叠在 KP 项下。**考试截断（建议）**：`interval=min(interval, max(1, floor(daysToExam/2)))`，保证考前至少再见两次。排序：逾期天数降序，强度升序。复习作答 purpose＝`RECALL_CARD`（仅练习级）。DeepTutor 两套复习互不相通（对比方案§四），这是合并的理由。

**nextAction（决定）**：①有未答完的题或讲到一半→继续它；②到期复习≥5 项且最老逾期≥1 天→“先复习 N 项”；③否则下一个 KP。

**日记（决定）** `JournalDay{date, facts, aiSummary?, humanNote?}`：facts 是投影（分钟数、触达 KP、新 passed/solid、答题数与正确率、复习完成/失败、追问数与主题）；aiSummary 来自 `JournalSummaryGenerated{date,text,basisSeq}`，basisSeq<当日末 seq 则标“已过期”，仅在打开记录或次日首开时生成，不覆盖人工区；humanNote 用 rev 做 CAS，冲突并排给用户选。日界线＝本地 04:00；事件自带 `tzOffsetMin`，投影因此是纯函数。

**教学模式（决定）**：注册表数据驱动（照 `SC/packages/domain/src/teaching-modes.ts`，9 种，UI 渲染注册表）。自动触发：

- 首讲＝`current`（平铺讲课，见 `~/DT/library/ECNM10119-健康经济学/AGENTS.md`）；同轮第 2 道错→自动 `example` 并换一道指纹不同的题；两轮仍未 passed→不强制，提示“直觉讲/费曼复述”。
- essay 标记 KP 已 passed→提示“费曼复述”，直到有≥0.8 的复述证据（用户 10-28 要求 essay 单独练，改造记录§七）；距考≤7 天→通过的 KP 上常驻“考试写法”按钮（不自动插入）。
- 手动随时可选，`LessonTaught.trigger=manual|auto` 如实记录。**开放**：`PASS_STREAK=3` 冲刺期是否过严（单常数可改）；solid 的“≥2 条、跨 2 天”是建议值。

## 3 领域模型与事件账本

一等对象：Course、Exam、Material/Page、Module、KP、Question（不可变，带指纹，rubric 冻结）、Thread/Turn、Note、Device。**派生不建表**：ReviewItem、JournalDay、KpState。

**决定**
- 内容与事实分离：大纲/材料/题目是内容库（内容寻址，不可变），学习事实只在账本。大纲变更用 `OutlineEdited`（add/rename/move/merge/retire），KP id＝永不重用的 ULID，合并留别名。理由：SC 的 CourseVersion N+1＋id 迁移是重量来源（`SC/packages/contracts/src/course/id-migration.ts`），DT 改名即换 id（改造记录§七）。
- 投影＝`fold(events)`，不做增量 Draft、不做持久投影缓存。SC 的增量 Draft/快照（`SC/packages/projections/src/*.ts`，约 2000 行）为 5 万事件基准而写；单用户一年约 5 万事件，纯 fold 应够用 [未证实，S3 实测]。
- 折叠顺序＝`(at, deviceId, deviceSeq)`，不是服务端 seq（seq 只作同步游标）。离线晚到的事件无论何时同步都落在同一位置，各端收敛。`at` 与 `receivedAt` 差>24h 时用 receivedAt 并标 `clockSuspect`。
- 客户端只能上送客户端类事件；判分、证据、讲解类仅服务端可写（防伪造）。

```kotlin
@Serializable data class Ev(
  val id: String, val seq: Long = 0,              // id=ULID，seq 服务端赋值
  val deviceId: String, val deviceSeq: Long,
  val at: Instant, val tzOffsetMin: Int,
  val type: String, val v: Int, val idem: String,
  val cause: String? = null, val courseId: String,
  val threadId: String? = null, val turnId: String? = null,
  val payload: JsonObject)
enum class Purpose { PRACTICE, RECALL_CARD, CLOSED_BOOK, UNSEEN_VARIANT, TIMED, MOCK }
enum class EvClass { CURRENT_OBJECTIVE, PRACTICE_ONLY, LEGACY_VERIFIED, USER_CLAIMED }
sealed interface Subject { data class Kp(val id: String): Subject
  data class ReviewItem(val id: String): Subject; data class Course(val id: String): Subject }
data class KpState(val kpId: String, val taught: Boolean, val passed: Boolean, val solid: Boolean,
  val claimed: Boolean, val shaky: Boolean, val evidence: List<EvidenceRef>, val lastAt: Instant?)
fun fold(events: List<Ev>, now: Instant): Learner   // 纯函数
```

**事件清单**（幂等键＝服务端唯一索引；重复提交返回原事件，不报错）

| 类型 | 关键字段 | 幂等键 | 产生者 | 撤销 |
|---|---|---|---|---|
| KpOpened | kpId, via | `open:{kp}:{deviceSeq}` | 客户端 | 否 |
| LessonTaught | kpId, turnId, mode, trigger, pages[], provider, textHash, ungrounded | `teach:{turn}` | 服务端 | 否 |
| QuestionIssued | qi, questionId, kpId, purpose, novelty, accepted, reviewedBy | `issue:{qi}` | 服务端 | 否 |
| HintRevealed | qi, level | `hint:{qi}:{level}` | 客户端 | 否 |
| AnswerSubmitted | qi, attempt, answer, elapsedMs | `ans:{qi}:{attempt}` | 客户端（可离线） | 否 |
| AnswerGraded | qi, verdict, score, max, rubricResults[], graderProvider, reviewState(exact\|verified\|unavailable) | `grade:{qi}:{attempt}` | 服务端 | `GradeSuperseded` |
| EvidenceRecorded | kpId, class, strength, gradeRef, decision | `ev:{gradeEvent}` | 服务端 | EvidenceRetracted |
| EvidenceRetracted | target, reason | `retract:{target}` | 服务端（用户确认） | — |
| ReviewCompleted | itemId, result, qi | `rev:{item}:{qi}` | 服务端（派生自判分） | 否 |
| ReviewScheduleSeeded | subjectId, dueAt, intervalDays, ease | `seed:{subject}:{hash}` | 迁移器 | 否 |
| AskTurnCompleted | threadId, turnId, scope, textHash, provider | `ask:{turn}` | 服务端 | 否 |
| NoteSaved / ReviewItemAdded | noteId, subject, textHash, refs | `note:{id}:{rev}` | 客户端 | 新 rev |
| UserOverride | kpId, kind(claim\|skip\|reopen) | `ovr:{kp}:{deviceSeq}` | 客户端 | reopen |
| JournalSummaryGenerated / JournalHumanEdited | date, basisSeq / rev | `jsum:{d}:{seq}` / `jedit:{d}:{rev}` | 服务端 / 客户端 | 新 rev |
| OutlineEdited / QuestionLinked | ops[] / questionId, kpId, basis | hash | 服务端（人工确认）/ 迁移器 | 反向 ops / 覆盖 |
| ExamSet / SettingsChanged / SessionSpan / LegacyImported | … | rev / LWW / span / hash | 客户端 / 迁移器 | 新 rev |

**投影（精确定义）**
- `KpState`：passed/solid/shaky 如 §2；`taught`＝存在未撤销的 LessonTaught。
- `Position`：`resume`＝唯一未答完的题（QuestionIssued 无 AnswerGraded 且未跳过）；否则最近的 taught∧¬passed∧¬claimed 的 KP；否则 `nextKp()`。
- `ReviewQueue(today)`：§2 日程的折叠；输出到期项、逾期天数、折叠关系；预计时长取历史耗时中位数，无数据显示“—”。
- `Journal(date)`、`Route`（模块→KP 徽章）均为 fold 输出。

**版本化**：事件带 `v`，读时用纯函数 upcaster 升到最新；未知类型原样保存、投影忽略（向前兼容）；投影声明 `PROJECTION_VERSION`，变化即重放。KMP common 无 ULID 库，自写约 40 行（单调时钟）。

## 4 服务端与存储

```
domain ─► protocol ─► server-core ─► server-ai ─► server-app
   │                       ▲              └─ tools-migrate
   └────► client-core ─► client-ui ─► app-android / app-desktop
```
左/上为底层，右/下可依赖左/上，反向禁止；domain 只依赖 serialization 与 datetime。仅靠 Gradle 模块图约束，不写门禁脚本。

**决定**：SQLite（WAL）单文件，库＝SQLDelight，服务端驱动 `sqlite-jdbc`。

```sql
CREATE TABLE event(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, idem TEXT UNIQUE NOT NULL,
  device_id TEXT NOT NULL, device_seq INTEGER NOT NULL, at TEXT NOT NULL, tz_min INTEGER NOT NULL,
  received_at TEXT NOT NULL, type TEXT NOT NULL, v INTEGER NOT NULL, course_id TEXT NOT NULL,
  thread_id TEXT, turn_id TEXT, cause TEXT, payload TEXT NOT NULL CHECK(json_valid(payload)),
  UNIQUE(device_id, device_seq));          -- 触发器禁止 UPDATE/DELETE
CREATE TABLE blob(hash TEXT PRIMARY KEY, kind TEXT, bytes BLOB);              -- sha256 内容寻址
CREATE TABLE page(material_id TEXT, pdf_page INT, book_page INT, text_hash TEXT, PRIMARY KEY(material_id, pdf_page));
CREATE VIRTUAL TABLE page_fts USING fts5(text, material_id UNINDEXED, pdf_page UNINDEXED, tokenize='porter unicode61');
CREATE VIRTUAL TABLE note_fts USING fts5(text, note_id UNINDEXED, tokenize='trigram');
CREATE TABLE turn_event(turn_id TEXT, n INT, ev TEXT, PRIMARY KEY(turn_id, n));  -- 断点续传，完成后压成 blob
CREATE TABLE lease(kp_id TEXT PRIMARY KEY, device_id TEXT, epoch INT, expires_at TEXT);
CREATE TABLE device(id TEXT PRIMARY KEY, name TEXT, token_sha256 TEXT, revoked INT);
CREATE TABLE id_alias(legacy_kind TEXT, legacy_id TEXT, new_id TEXT, PRIMARY KEY(legacy_kind, legacy_id));
```
- **页引用**：规范形态 `PageRef{materialId, pdfPage}`，书页由 `bookPageOffset` 推（本教材 31，改造记录§五），前言页为空；提示词标签 `[m3:p38]`，界面显示“书第 7 页（PDF 38）”。
- **检索**：教材英文、笔记中文，unicode61 不切中文，故分两张表（英文 porter、中文 trigram，≥3 字；2 字退化 LIKE）。ask 检索＝KP 锚点页∪ASCII 词 FTS∪KP 中英术语表∪笔记 trigram。订阅里没有 embedding（改造记录§五），M1 不做向量、不为检索多花一次模型调用 [召回质量未证实，S2 一并测]。
- **备份**：每晚 03:30 `VACUUM INTO` 到 `~/CoachData/backups/`，留 14 日＋8 周；迁移前强制备份；另追加 `events-YYYYMM.jsonl`，库损坏可由 jsonl＋blob 重建。第二落点（如 iCloud）涉第三方存储，**开放**。
- **租约**：粒度＝KP 教学线程，TTL 90 秒心跳续期，持有者 `(deviceId, turnId)`，`epoch` 作 fencing token（DT 的 `mastery_path_leases` 是路径级、无 epoch）。别的设备打开同一 KP 给“接管/旁观”；接管使 epoch+1，旧持有者之后的状态类事件被拒。ask 不需租约。
- **多设备冲突**：账本是只增集合＋确定性折叠，天然收敛；仅租约、日记人工区（rev CAS）、大纲编辑（按 op 合并）、设置（LWW）会冲突。

## 5 AI runtime

**决定：服务端驱动的结构化状态机。** 理由见 §0.1；SC 的结构化载荷能做可校验的出题/批改（`SC/packages/ai-runtime/src/orchestrator.ts`）但无流式与对话，DT 流式与对话好但流程靠提示词，Coach 各取一半。

```
PREPARE(检索，无模型) → TEACH(流式) ──┬─ ask*(任意时刻，只读)
TEACH → 来练习 → QUIZ(取预生成题) → AWAIT → GRADE → FEEDBACK(流式)
FEEDBACK → 下一题 | EVALUATE ;  EVALUATE: passed→收尾+排复习 ; 否则按 §2 自动换讲法→TEACH
```
- 全程**没有 tool-calling**，只有流式纯文本（讲解、反馈、ask）与 JSON 模式（出题、批改、复核）两种调用；桥现成支持（`~/DT/tools/subscription_bridge.py` 的 `json_instruction`/`clean_json`）。
- **预生成**：讲解流结束时后台已在出 3 道题（一次调用）＋1 次盲复核，点“来练习”零等待。出题 Claude、复核 Codex（改造记录§三模型对比）。共用桥的并发槽（Claude 3/Codex 2/agy 2），优先级：用户流>复核>预生成。
- **盲复核**：复核方只拿题干、选项、来源片段，**不拿 key**，自己作答并指出歧义/多解/无解；key 一致且无缺陷才 `accepted`，不一致重出一次，仍不一致弃用。简答同理：盲评同一冻结 rubric，分差>1 个 rubric 点→`reviewState=unavailable`，展示反馈但不入证据（即 SC 的 `accepted:false` 硬边界）。
- **来源归属**：提示词给带标签片段 `[m3:p38]`；流式过滤器按“标签∈本次片段集合”实时校验（近乎零成本），合法标签转 `cite` 事件，非法的丢弃计数；无合法标签且含数字/定义的段落标 `ungrounded`，显示“教材外/未核对”，不阻断（SC 的四类归属见 `SC/.../source-validation.ts`，此处简化）。
- **逐字证据批改**：模型只填 `rubricResults[{criterionId, awarded, studentEvidence}]`；服务端校验条目/文字/分值未变、总分算术、`awarded>0` 时 `studentEvidence` 是原答的逐字子串且≥3 token、≥8 有效字符（移植 `SC/.../grade-validation.ts`，汉字单字计 token）。失败→一次修复→仍失败则 unavailable。选择题由服务端精确判分，不调模型。
- **预算**：每个状态改变决定（出题、批改）≤3 次物理调用（主调用、一次重试或兜底、一次复核）。SC 是 2 次（`SC/docs/architecture/adr-0003-single-ai-execution-runtime.md`），Coach 复核必跨家故取 3 [建议]；`TurnCompleted.calls` 如实记录。
- **链**（`ModelProvider.stream(req): Flow<ProviderEvent>`）：teach/feedback/ask＝Claude→Codex；出题＝Claude；复核＝Codex→Claude（必须≠主调用实际服务方）；Grok/Antigravity 只作用户点名的“另一位导师”，不进自动链（Antigravity 有封号风险，改造记录§四）。跨家兜底会把学习内容送给另一家，SC 要求显式同意，设置里勾选。
- **取消/超时**：`cancel`→取消协程→适配器断开→桥杀整个 CLI 进程组（改造记录§三已验证无残留）。首字 8 秒无增量显示“还在想…”，30 秒给“换导师”；桥内首字上限 90 秒、总上限 180 秒。出字前失败→换链；**出字后失败**→`error{truncated}`＋“继续写完”（以已出尾部为上下文再请求；桥目前只能截断）[建议]。
- **复用桥**：M0–M1 用 `BridgeProvider` 调 `127.0.0.1:8790`。缺口：流式块的 `model` 是请求值（`live_stream_response`），兜底后真正服务方不可见 [兜底路径未证实]，而复核独立性要知道谁答的——需加“不兜底”开关与 `x-served-model` 头（改桥的副本，本轮不改）。**替换时机**：契约稳定后（M2）用 `ProcessBuilder` 重写 Claude/Codex 适配器（约 300 行，去掉 Python 与一跳 HTTP），沙箱脚本继续复用。
- **真实验证而非 mock**：(1) Fake provider 只准出现在状态机单测；返回固定文本的路径必须打 `source=fixture` 并显示“演示数据”，release 不含。(2) **植入缺陷测试**：喂错 key 的选择题，复核必须不同意；喂 `studentEvidence` 不在原答里的批改，必须被拒——抓“永远同意的假复核”。(3) 每个 AI 事件记录实际 provider/model，“复核≠主调用”由测试断言。(4) `live-loop` 脚本用真实服务端与 CLI 跑 3 个 KP 整圈并存转录，QA 必须自己复跑。

## 6 客户端-服务端协议

版本：`/api/v1`，`GET /hello`→`{protoMin, protoMax, build, eventMaxV:{type:v}, features[]}`。客户端协议号不在区间→阻断提示“请更新”，服务端提供 `/app.apk`。**所有 DTO 忽略未知字段**（DT 的 `TurnRequest` 是 `extra="forbid"`，升级即拒旧客户端，见对比方案§八）。

| 端点 | 作用 |
|---|---|
| `POST /events`、`GET /events?after=` | 批量追加（拉取游标＝seq） `{events:[Ev]}`→`{results:[{id, seq?, status:stored\|duplicate\|rejected, reason?}]}`；确定性判分（选择题）同步返回 `inline:[产生的事件]`，在线即时反馈 |
| `POST /threads/{id}/turns` | `{kind, text?, mode?, provider?, clientTurnId}`→`202 {turnId}`，clientTurnId 幂等 |
| `GET /bundle/offline` | 离线包：题（未见题无 key，已见题带 key 与解析）、已讲全文、页、公式 SVG |
| `GET /outline`、`/page/{m}/{p}`、`/search`、`/math/{hash}.svg` | 内容读取（ETag；SVG 为 immutable） |
| `POST /lease/{kp}`、`POST /pair`、`GET /health` | 租约→`epoch`；配对；提供方状态 |

WebSocket `/ws`（承载全部实时）：客户端 `{"op":"sub","topic":"turn:T","after":12}`、`{"op":"sub","topic":"ledger","after":4821}`、`{"op":"cancel","turn":"T"}`、`{"op":"ping"}`；服务端 `{"topic","n","ev"}`，turn 事件：`delta{text}`、`cite{ref,pdf,book}`、`math{hash,w,h,base}`、`card{kind,qi,payload}`（无 key）、`state{lesson}`、`provider{id,model}`、`error{code,retryable}`、`done{usage}`。服务端把 delta 合并为≥40 字或 80ms 一块并持久化到 `turn_event`；重连带 `after=n` 从断点补发，已完成的 turn 重放后补 `done`。断点续传同 DT 的 `resume_from/after_seq`（`DT/api/routers/unified_ws.py`），字段自定并版本化。

**离线作答幂等**：outbox 按 deviceSeq 顺序发；`stored|duplicate` 都算成功并出队；`rejected` 进“同步问题”列表，**不静默丢**。离线时未见题的 key 不在设备上，先记首答再批改，保证首答纯度。

**认证（决定）**：服务端只听 `127.0.0.1`，由 `tailscale serve` 提供 HTTPS（*.ts.net 证书）并转发；Android 默认禁明文 HTTP，这样不必写网络安全例外。每台设备配对一次：Mac 显示 6 位码（5 分钟有效）→`POST /pair`→下发 256 位随机 token，服务端只存 SHA-256，端上放 Keystore/Keychain，可吊销；Mac 本机客户端用 `~/CoachData/local.token`（0600）。不要像 `bind_lan.sh` 监听 0.0.0.0。[HTTPS 需 tailnet 管理端开启，tailscale CLI 不在 PATH；均未证实，S0 验证。退路：Tailscale IP 明文＋网络安全配置。]

## 7 客户端架构

```
domain/ protocol/ client-core/ client-ui/ app-android/ app-desktop/
server-core/ server-ai/ server-app/ tools-migrate/ testing/      gradle/libs.versions.toml 一处锁版本
```
`client-ui` 的 expect/actual 只有：`FoldState`（Android 用 androidx.window，桌面恒 Flat）、`SecretStore`、`FileStore`。Kotlin＋Compose Multiplatform 版本在 S0 取当日最新稳定并锁定。

| 项 | 选 | 理由 | 备选 |
|---|---|---|---|
| 网络 | Ktor client（Android OkHttp、桌面 CIO）＋kotlinx.serialization | 三端同一 API | — |
| 存储 | SQLDelight，客户端服务端共用 `.sq` | 事件溯源要手写 SQL | Room KMP |
| 导航 | JetBrains navigation-compose（typed routes） | 官方、依赖最少 | Decompose |
| 自适应 | 自己用 `BoxWithConstraints` 算窗格（§8），只取 WindowSizeClass | 窗格规则要可测可预期 | NavigableListDetailPaneScaffold |
| 状态/DI | androidx.lifecycle ViewModel（KMP）＋`StateFlow<UiState>`；手写 `AppGraph` | 不引 MVI 与 DI 框架 | Koin |
| Markdown | JetBrains `markdown` 解析，自写渲染 | KMP、GFM 表格；现成渲染库无增量与数学钩子 | mikepenz renderer |
| 公式 | 自写 `MathSvgPainter`（下） | 见下 | WebView/JCEF＋KaTeX |

**流式 Markdown＋LaTeX（决定）**
1. **规范化**：入口过 Kotlin 版 `normalizeMath`，移植 `SC/packages/domain/src/math-markdown-policy.ts`（`\[..\]`→`$$`、重复反斜杠、裸变量包 `$`），都是真实模型的失误形态。
2. **增量分块**：按空行/围栏切块；已封口块不可变，key＝`turnId:序号:内容哈希`，只解析一次；仅尾块每 33ms 重解析。未闭合的 `$`/`$$` 在流中显示固定高度（约 48dp）骨架，闭合后才换成公式，避免半截 LaTeX 乱码和高度抖动。
3. **公式渲染**：服务端常驻 `mathd`（Node＋MathJax 3 `tex-svg`；Mac 已有 nvm node 22，由 Ktor 监督），每个 `(latex, display)` 得 SVG 与 `{w,h,baseline(em)}`，按 sha256 缓存。客户端 `MathSvgPainter` 只解释 MathJax 输出的子集（`defs/path`、`g/use` 的 translate/scale、`rect`、`<text>`）画到 Canvas，颜色取主题前景色，**暗色与字号缩放不必重渲染**。行内公式用 `InlineTextContent`＋`Placeholder` 做基线对齐；块公式单独一行，过宽横滑。公式里的中文（`\text{保险费}`）走 `<text>`，由 Compose 用主题字体画 [未证实，S1 必测]。
4. **降级阶梯**：SVG 未到→骨架；`mathd` 不可用或超时 2 秒→等宽 LaTeX 源码 chip（可读可复制）。绝不空白、不乱码。
5. **滚动稳定**：LazyColumn 每条消息一个 item，块是子项；“贴底”＝距底≤24dp 才自动跟随，用 `scrollBy(增量)` 而非 `animateScrollToItem`；用户上滑即停止跟随并出现“回到最新”。尾块在最底，增高只推走视口外内容。
6. **逐平台**：Android 与桌面（Skia）走同一套 Compose Canvas，无平台分支；差异只在 `<text>` 字体回退与 HiDPI 取整，S1 两端各测；Windows 沿用桌面实现。
7. **退路**：S1 不达标则 Android 用“整条消息一个 WebView＋KaTeX”、桌面同一份 HTML 进 JCEF（体积大、首启下载 Chromium，约 +1–2 周）。

**离线缓存**：客户端 SQLite 镜像全部事件、大纲、离线包、outbox，另有公式文件缓存（LRU 约 50MB）；连通性只信 WS 与 `/hello` 退避重试。

**工具链现状（实测）**：JDK 17.0.20（Homebrew）✓；Android SDK 在 `/opt/homebrew/share/android-commandlinetools`：build-tools 34/35、platforms 34/35、emulator、AVD `sable-api35` ✓（`ANDROID_HOME` 未设，`adb` 不在 PATH）；Node 22 ✓；`~/.gradle` 15GB，已有 wrapper 分发 8.11.1 与 9.5.0（Compose 依赖是否已缓存 [未证实]）；Gradle/Kotlin 不必另装。**缺口**：①项目 wrapper 与 `libs.versions.toml`；②折叠屏 AVD（现为手机形态，用 `adb shell wm size 1848x2448; wm density 420` 模拟，改造记录§八已用）；③Compose Desktop 在 macOS 27 beta 上的运行与 `jpackage` [未证实]。

## 8 自适应与折叠屏

dp 按 420dpi 估算（外屏 475×751、内屏 704×933，同改造记录§八），运行时读真实值。**窗格规则（决定）**：导航轨道 72dp；对话最小 520、阅读最大宽 720 居中；引用/教材面板 320；路线列表 300。从对话开始按优先级 对话>引用面板>路线列表 依次加入，放不下就不加。

| 形态 | 尺寸 | 布局 |
|---|---|---|
| 外屏竖 | 475×751 | 单窗格＋底栏 5 项；引用为底部抽屉（60%）；作答卡贴底；块公式横滑 |
| 外屏横 | 751×475 | 轨道＋对话；**高度紧**：隐藏顶栏；IME 弹出进“聚焦布局”（只留当前题干＋输入框，可逆） |
| 内屏竖／Pad Mini 竖 | 704×933／750–840 | 轨道＋对话（632）；引用/大纲为右侧覆盖面板，按钮开关 |
| 内屏横 | 933×704 | 轨道 72＋对话 520＋引用 320＝912，三栏；无路线列表 |
| Pad Mini 横 | 1120–1260 | 轨道＋路线 300＋对话 520＋引用 320＝1212；不足则去路线列表 |
| Mac 窗口 | ≥640，默认 ≥1100 | 同规则，连续缩放 |

**折叠/展开状态保持（决定）**：Activity 声明 `configChanges`（screenSize|smallestScreenSize|screenLayout|orientation|density|keyboardHidden）不重建；对话面板用 `movableContentOf` 跨布局保留 LazyListState、输入框、选择状态；滚动锚点存 `(turnId, blockIndex, 像素偏移)` 而非 item 下标；草稿、已选选项、提示等级、引用面板目标页进 ViewModel 并 `rememberSaveable`；WS 流不受影响。**IME 与流式**：作答卡 `imePadding`；打字时只有“贴底”才自动滚；简答框最多 6 行再内滚；触控目标≥48dp，主操作放下 40%。半折叠桌面姿态归 M3 [建议]。

## 9 设计系统

**决定**：令牌三层保留，实现成 Kotlin：`CoachTokens`（4dp 间距阶、圆角 12/18/28/36/全圆、时长 120/200/320ms、缓动 (0.2,0,0,1)/(0.3,0,0,1)）与 `CoachColors`（`light()/dark()`，数值照搬 `SC/packages/design-tokens/tokens.css`：画布 `#f4efe6`、卡面 `#fffaf2`、主按钮 `#bd4070`；暗色底 `#191712`、主色 `#f088b0`），映射到 Material3 `ColorScheme`，状态色（fg+bg）放 `CompositionLocal`。用 M3 取其无障碍与组件行为，形状字体全覆盖，关闭动态色。

**对比度（实算）**：正文 13.4:1、次要 4.99、主按钮白字 5.08、状态色 4.63–5.40，均过 AA。**`fg-tertiary` 亮 2.53、暗 4.14 不过**，规则：只用于装饰与禁用，不承载信息。一个单测覆盖这十几对颜色即可。

**字体（建议）**：标题（≥19sp）内置 Noto Serif SC 子集（SemiBold、GB2312 级字符，预计 1.5–3MB [未证实]）；正文用系统无衬线。理由：SC 标题栈 `Iowan Old Style/Songti SC/STSong` 仅苹果平台有；全文内置太重。缺字回退系统字体 [未证实，S1 顺带验]。

**其余**：流式文字无动画，尊重“减少动效”；毛玻璃顶栏降级为 92% 实色（backdrop blur 要额外库，**开放**）。

**组件与状态**：Button（主/次/文字×可用/按下/禁用/加载）；KpRow（未学/已讲/练习中/已通过/稳固/自称/不稳/软锁）；MessageBubble；StreamingTail（流式/停滞/出错/截断+继续）；McqCard（未答/已选/已交/对/错/离线排队）；ShortAnswerCard（含证据引文）；CitationChip；MathBlock（骨架/就绪/源码降级）；JournalCard（含“已过期”）；Banner；Empty/ErrorState（必带下一步动作）。

## 10 DeepTutor → Coach 迁移

**决定**：`tools-migrate` 只读 DeepTutor，**幂等可重复**（确定性 ULID＋`id_alias`＋事件幂等键）；现在只 dry-run，期中后正式跑（DeepTutor 到 11-02 仍在写）。输出 `migration-report.md`：两侧计数、未决项、差异解释。

| 来源（实测） | 目标 | 规则与坑 |
|---|---|---|
| `~/DT/library/ECNM10119-健康经济学/` 教材 24 章 md、笔记、资料 | Material＋Page＋FTS | 解析 `<!-- PDF 第 N 页 · 书第 M 页 -->`，`bookPageOffset=31`，校验页号连续；`答案/` 与 `~/DT/sealed/` 不进可检索库与导师上下文 |
| `learning_journal/journal.json`（不足 1KB）＋`AGENTS.md` | Course＋Exam＋TutorPersona | 期中 2026-11-02 14:25（Europe/London），目标 80，范围 Week 1–6；“平铺讲课”写入人设 |
| `mastery.sqlite3` 路径 `topic_706ba859…`：12 模块 49 KP（memory 26/procedure 23，前置全空） | Module/KP | 先按 DT id 匹配→再按“模块序号＋名称哈希”→否则新建并进报告（DT 改名换 id，改造记录§七）；9 个“【Essay】”KP→`tag=essay`，原类型取 `tools/patch_backups/gate_types_before.json`；`topic_source_ids`→锚点页 |
| 路径 `topic_dd61e4…`（62 KP、61 条前置边、0 作答） | 不迁 | 前置边存为候选文件 |
| `chat_history.db` 的 `notebook_entries`：108 条 `external_import`（60 单选/48 简答），`knowledge_point_id` **全空** | Question（import） | 指纹＝sha256(规范化题干＋选项)；类别关联（540 行：Ch/Week/MCQ/Essay/Analytical）→tags；**KP 归属三步**：Ch/Week→模块；解析里 `〔书第 a–b 页〕` 与 KP 锚点页重叠；仍并列则 AI 在本模块内选并盲复核；`QuestionLinked.basis=inferred`，可改，未决进“未归类”桶。**迁入后先批量盲复核 key 才 accepted**；48 道简答须生成并冻结 rubric |
| 笔记本 `notebook/c1388fac.json`、`data/digests` | Note(digest) | 挂章节模块，只读 |
| 路径内 17 条 quiz_attempts/learning_evidence、5 个 mastery_levels＝1.0；`mastery_events` 有 attempt.recorded 20、overridden/cleared 各 25；`assessment_attempts` 20 | QuestionIssued＋AnswerSubmitted＋AnswerGraded(exact)＋EvidenceRecorded | `class=LEGACY_VERIFIED`，`at` 保持原时间；计入 passed 不计 solid（`SC/docs/architecture/mastery-evidence-classification.md`）。**20 与 17 的差异**必须在报告逐条解释 [原因未证实] |
| `repetition_states` 5、`review_queue` 5；`practice_review_state` 0 | ReviewScheduleSeeded | 保留原 `next_review_at`，不重算 |
| 会话：1 会话/86 消息/45 轮/3318 turn_events | 只读归档 Thread | 有 mastery_events 关联的挂 KP，其余挂 course；不进投影；同时是 S1 渲染语料 |
| 阅读集 13、复习书 6、看板 7、人格 | 不迁 | 阅读集由 Material 重生成；人格进 TutorPersona |

**验证**：5 个已掌握 KP 为 passed；17 条作答可下钻；108 题指纹零重复；页号连续。

## 11 测试与验证

原则：只测用户会感知的质量，不写数文件/行数/覆盖率的记账式断言。

**领域属性测试**（kotlin-test＋自写生成器，每条 10k 随机用例）：P1 事件任意到达顺序折叠结果相同；P2 重复追加不改变投影；P3 缺 `evidenceContext` 的作答永不成为 `CURRENT_OBJECTIVE`（fail closed）；P4 `fold(E+retract(e))==fold(E去掉e)`；P5 加对的新题不会让 passed 变 false、加错题不会让未 passed 变 passed；P6 复习间隔≥1 天、ease∈[1.3,3.0]；P7 旧版本事件升级后折叠等价。

**黄金用例**：移植 `SC/packages/domain/test` 的证据判定表（0.8 边界、hints 2/3、重复题、重试、无锚点）[未逐条核对]，加迁移后的 5 个 KP 与 17 条作答。

**UI（用户感知）**
- 流式回放：把 DeepTutor 真实 turn（含公式、表格、模型写坏的 `$`）按原时序灌进渲染器，在模拟器与桌面各跑；量丢帧率（JankStats）、已封口块上方位移＝0、公式闭合到可见时延、最终文字与源文本一致。
- 折叠：流式中用 `wm size` 切换外屏/内屏/横竖，断言同一 turn 同一块仍在视口、草稿与已选选项仍在（语义树）。
- 承诺：脚本数“打开→开始学习”点按≤3；教学首字 p50/p90（目标 p50≤5s，改造记录§三实测约 4s）；字号 2.0× 时主操作不被裁。

**AI 链路真实端到端**：见 §5“真实验证”；另做两次演练——杀掉 claude 进程（出字前→应换链；出字后→应截断并可继续）；停服务端答 5 道复习题再恢复→账本里每条恰好一次。

## 12 里程碑、spike、风险、团队

| # | Spike | 判定 | 失败退路 |
|---|---|---|---|
| S0 半天 | 工具链：wrapper、KMP 空壳同跑 `sable-api35` 与 Mac 桌面；JVM/Android 建 FTS5 表 | 两端启动；`PRAGMA compile_options` 含 FTS5；记构建耗时 | FTS 手写 SQL；换 Room KMP |
| S1 渲染（最大风险） | `mathd`＋`MathSvgPainter`＋增量分块器；语料＝20 条真实 turn＋10 条最坏 LaTeX | ≥98% 公式与 KaTeX 参考肉眼一致，其余降级源码不空白；内屏竖 1500 字含 6 公式流式丢帧≤3%、封口块零位移；闭合到可见≤300ms | WebView＋KaTeX（§7） |
| S2 经桥真流式 | `BridgeProvider` 对 20 条真实大小提示（片段≤12K 字）测首字；取消无孤儿进程；served model 可见；预生成是否挤占槽位 | 首字 p50≤5s、p90≤8s | 缩小片段；预生成走 Codex 池 |
| S3 账本＋属性 | 事件表、fold、P1–P7；5 万合成事件重放耗时；17 条迁移作答黄金 | 属性全过；耗时如实报告 | 才引入增量快照 |
| S4 离线＋折叠 | outbox/同步/租约；中流切形态 | §11 折叠与离线演练通过 | — |
| S5 迁移 dry-run | 对只读副本跑 | 报告生成，未决项<15% | 人工归类 |

顺序 S0→（S1∥S2∥S3）→S4→S5，约 1 周。

**里程碑**：M0＝spike＋ADR＋协议/事件 JSON 样例冻结。**M1＝闭环**，垂直切片：账本与折叠→复习（最不依赖 AI，先有日用价值）→练习与判分→讲解流→今天页→日记；内容仍用 `build_ecnm10119.py` 导入。出口：立项方案§八三条＋掌握度可一层下钻到原题/原答/判分依据。M2＝多课程导入、模考与双轨、来源阅读器；M3＝Windows、通知、tabletop。

**第一版不做**：阶段机与模考、Windows、多课程导入 UI、通知/小组件、手写拍照作答、语音、embedding 检索、tool-calling 循环、导师自选资源、自适应难度、账号体系、任何数字门禁与台账。

**团队拆分（防踩踏）**：Gradle 模块就是所有权边界。

| 岗位 | 拥有 | 规则 |
|---|---|---|
| 架构师（Claude） | `domain` 事件/协议 schema、ADR | 事件与协议在 M0 末冻结，v1 内只增不改；改动先发“接口卡” |
| 工程主力（Codex 主、CB-Flash2 副） | `domain` 实现、`server-*`、`tools-migrate` | 同时执行卡≤2；一卡一模块 |
| 前端/安卓（Builder-C；Draft 出初稿） | `client-core`、`client-ui`、`app-*` | 依 protocol 的 JSON 样例开工，不等服务端 |
| 验收（QA/QA-C） | `testing`、真实 e2e | 必须自己复跑；首行“达到/部分/未达到＋数字” |

跨模块改动拆成“接口卡＋两侧实现卡”；Gradle 构建共用文件锁串行。

**风险**

| 风险 | 对策 |
|---|---|
| 公式/流式渲染（最大） | S1 先行，退路已定 |
| Mac 休眠使手机连不上 | 离线包；保持唤醒或迁常驻机属系统/账户变更，由用户拍板 |
| 订阅额度与风控（Claude 3 槽与用户共用；Antigravity 条款） | 预生成降优先级；Antigravity 不进自动链；记录 `calls` |
| 题量不足、出题质量、macOS 27 beta 上的 Compose Desktop [未证实] | 盲复核＋植入缺陷测试（§5）；S0 第一天验证 |

**开放问题（按节）**：§2 `PASS_STREAK` 与 solid 阈值；§4 服务端保持唤醒/迁常驻机、备份第二落点；§5 是否允许跨家兜底；§6 tailnet 能否开 HTTPS；§7 Compose Desktop 在 macOS 27 beta 上能否跑；§8 tabletop 姿态；§9 字体内置、毛玻璃；§10 20 与 17 的差异、108 题归属质量。其余节无。
