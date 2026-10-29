# Coach 完整设计提案

“决定”表示本提案的明确立场；新增决定仍由主持人整合裁决。“建议”表示可调整参数；“开放问题”不阻挡无关工作。本轮仅做只读核查，未构建或实机验证。篇幅按本次完整任务的 ≤6500 字要求。

路径缩写：

- `S`＝`/private/tmp/claude-501/-Users-zheng-li/af30aae9-a910-489f-aa59-a62aa8ea8550/scratchpad/sc/src`
- `D`＝`/Users/zheng_li/DeepTutor/.venv/lib/python3.12/site-packages/deeptutor`
- `B`＝[`/Users/zheng_li/DeepTutor/tools/subscription_bridge.py`](/Users/zheng_li/DeepTutor/tools/subscription_bridge.py)

## 核心赌注

1. **学习不中断，比功能齐全重要。** 押“打开继续、断网能答、展开不丢”。Mac 休眠是确定存在的架构限制，所以离线基础能力必须进入 M1。依据：[立项方案](/Users/zheng_li/DeepTutor/新应用立项方案v2.md)。
2. **通过与稳固必须分开。** 押“冲刺允许选择题过关，稳固要求独立迁移证据”。否则要么重现答题不推进，要么把记住答案包装成掌握。依据：`D/learning/mastery.py`、`S/packages/domain/src/mastery-evidence-policy.ts`。
3. **先证明真实 AI 和公式体验，再扩产品面。** 押“服务端编排、模型提供内容；Compose 管交互，浏览器引擎管富文本”。最大风险是跨端渲染和桥的完成语义，并非 Kotlin 领域逻辑。

**必须修正的集成前提：**B 未注册 Grok；仅 Claude 非 JSON 请求真流式；兜底响应仍报告请求模型名；中途异常后仍可能发送结束标记。直接原样复用会妨碍完整性与独立复核判断。四导师能力不能据此宣布完成。

## 1. 产品与学习闭环

**决定：**每天进入“今天→继续当前对象→讲/练/批→下一步”，冲刺只改变排序和讲法，不删课程范围。手机碎片时间默认一次小任务；折叠展开只换布局。断网/Mac 休眠时阅读缓存、继续已下载题、保存答案，显示“待同步/待判”，不虚构 AI 回复。

| 屏幕 | 主动作 | 空状态 | 错误态 |
|---|---|---|---|
| 今天 | 继续 | 导入课程 | 缓存任务＋连接状态 |
| 路线 | 学这个点 | 大纲待导入 | 保留旧版，重载 |
| 学习 | 当前步：回答/继续 | 开始讲解 | 原答不丢，重试失败步骤 |
| 复习 | 开始一组 | 已清空＋下次日期 | 待判题保留 |
| 记录 | 写今天 | 空白人工区 | 保存本地，显示冲突 |
| 资料 | 打开引用 | 尚无资料 | 缺页说明＋其他来源 |

学习是对象详情页，不另占一级导航。

三种对话：`Learn(KP)`执行教学；`Review(ReviewItem)`只影响复习；`Ask(Course)`可附当前 KP，只记问答。追问不自动评分；测验中索取答案会记录辅助暴露。随口问自动归档到课程，可一键存笔记/加入复习；不确定的知识点关联只作建议。

上下文由对象关联资料、笔记、题库生成。默认一位导师；“换种解释/请另一位核对”触发角色路由，显示实际导师名，不要求选资源。依据：`S/apps/web/src/learn/tutor-panel.tsx`、`D/capabilities/mastery/binding.py`。

**建议：**冲刺首页仍只有一个主动作，按到期、薄弱、考试范围排序，并展示理由。  
**开放问题：**每日学习时长偏好；未设置时不推定配额。

## 2. 学习规则

**决定：**

- **已通过：**最近五个不同题族的首次有效作答，按旧到新权重 `.5,.7,.85,.95,1` 加权，取末 n 项；≥`.9` 且 n≥3。选择题可计，重试只进修复分。保留 DeepTutor 防止一题碰巧答对的保护，新增题族去重。依据：`D/learning/mastery.py`。
- **稳固：**最近两次严格测评均≥`.8`，跨两个学习日，至少一次非选择题未见变体；首次、无答案暴露、提示<3、题目与评分已复核。提示只能是流程提醒；内容提示使其降为练习。信息缺失不认严格证据。沿用 StudyCoach 资格，增加重复证明，避免一次发挥决定长期标签。
- 两层分别计算；稳固意味着通过。后来正式测评失败可降级，历史成就保留；普通复习失败只显示“需巩固”，不改掌握。
- 前置依赖用于推荐补课，不硬锁阅读/冲刺；显式跳过不等于通过。空依赖图标“未审定”，不能假装已核验。
- 保留定向、通读、深攻、模考、修复作计划视图；证据资格不受阶段标签限制。阶段由显式事件推进，时间不推进。
- 两套复习合为同一调度器：目标为 KP 或题目，同 KP 聚成一张卡；仅更新本次明确覆盖目标。未做的108题是候选题，不是108个错题。先看队列，点开始才出题。
- 日记＝学习事实、薄弱点、下一步、AI摘要、人工文本；首做分与修复分分栏。

**建议：**复习间隔依次1/3/7/14/30天；≥`.8`且无内容提示升一级，`.6–.8`维持，低于`.6`回1天。阶段建议：确认计划→通读；每点读完或显式略过→深攻；通过覆盖≥80%→推荐模考；模考完成→修复。模考随时可手动进入。

新知识自动直觉讲，连续两次错误建议换例子，冲刺建议考试写法；九种讲法均可手动切换。依据：`S/packages/domain/src/{learning-phase,teaching-modes}.ts`。

**开放问题：**这些是产品初值，非已验证学习效果；两周真实记录后调整，规则版本随事件保留。

## 3. 领域模型与事件账本

**决定：**一等对象为课程版本、知识点、材料页、题目版本、作答、评分、对象对话、复习目标、学习计划、日记。ID不随名称变化。

```kotlin
data class ObjectRef(
  val courseVersionId: String,
  val kind: ObjectKind, // COURSE / KP / REVIEW
  val id: String
)
data class Event(
  val id: String, val seq: Long,
  val schemaVersion: Int, val ruleVersion: Int,
  val commandId: String, val effectIndex: Int,
  val actorId: String, val deviceId: String?,
  val objectRef: ObjectRef, val causationId: String?,
  val occurredAt: Instant, val acceptedAt: Instant,
  val localDate: LocalDate, val zoneId: String,
  val payload: Payload
)
data class Submission(
  val id: String, val assessmentId: String,
  val questionVersion: String, val familyId: String,
  val answerHash: String, val purpose: Purpose,
  val exposureIds: List<String>, val offline: Boolean
)
data class RubricResult(
  val criterionId: String, val earned: Int, val maximum: Int,
  val answerSpans: List<IntRange>, val reason: String
)
```

所有账本事件由服务端验证后追加。幂等键统一为`(actorId,commandId,effectIndex)`；模型不能直接写事件。

| 事件族 | 必需载荷 | 来源／纠正方式 |
|---|---|---|
| CoursePublished/Migrated | 内容hash、版本、ID映射 | 导入器／追加新版本 |
| PositionSet/StepCompleted | KP、step、预期版本 | 用户／追加纠正 |
| ThreadOpened/MessageRecorded | 对象、角色、正文hash | 用户/runtime／保留历史 |
| AssessmentIssued | 题目、rubric、答案hash、题族、用途 | runtime／作废 |
| QuestionExposed/HintUsed | 测评、辅助级别 | 服务端接受客户端事实／不可抹除 |
| AnswerSubmitted | Submission | 用户／新尝试 |
| GradeCommitted | 作答、逐项分、复核凭据、替代评分ID | 评分器／追加更正 |
| ReviewCompleted | gradeId、覆盖目标、结果 | 规则引擎／撤销重算 |
| PhaseChanged/OverrideRecorded | 原阶段、目标、依据、理由 | 用户＋规则／追加回退 |
| NoteSaved/JournalEdited | 内容hash、日期、baseRevision | 用户／新版本 |
| SummaryRecorded | 输入seq范围、内容hash | runtime／新版本 |
| LegacyImported/FactVoided | 来源hash或目标事件、原因 | 导入器/纠错／保留审计 |

投影统一为`fold(events≤seq, ruleVersion)`：

- 位置：最后一个通过版本校验的显式续接事件；后台评分不移动用户。
- 掌握：有效评分及其作答资格按第2节计算。
- 复习：按seq折叠调度事件；“是否到期”另接显式`asOf`参数。
- 阶段：最后一次有效转换。
- 日记：事件已记录的本地日期分桶，分别选摘要和人工修订；投影不调用AI。

题目作废连带排除其评分、掌握与调度影响，但保留原答。采用事件上转换器，历史不原地改写；投影版本变化全量重建。依据：`S/packages/contracts/src/run/learning-event-v1.ts`、`S/packages/projections/src/`。

**建议：**分数用整数分值，避免跨端浮点边界不同。  
**开放问题：**跨时区历史日期默认不重分桶。

## 4. 服务端与存储

**决定：**依赖方向为`apps→application→domain/contracts`；`storage、ai-adapter`实现application端口，domain不依赖网络、数据库、Compose。Ktor单进程＋SQLite单写者，适合个人规模。

```text
events(seq PK,id UNIQUE,actor,command_id,effect_index,...,payload_json)
commands(actor,id PK,payload_hash,result_json)
objects(id PK,kind,course_version,content_hash)
blobs(hash PK,mime,size,path)
pages(id PK,material_hash,pdf_page,book_label,text_hash)
anchors(id PK,page_id,start,end,quote_hash)
relations(from_id,to_id,kind)
projection(name,version,last_seq,state_hash)
leases(kp_id PK,owner,expires_at,fence)
jobs(id PK,state,input_hash,attempt,budget)
stream_frames(turn_id,seq,frame_json, PK(turn_id,seq))
import_map(source_system,source_id,target_id,source_hash)
```

对象索引和投影可由事件＋不可变blob重建；教材页码保存实际映射，`pdf_page`从1开始，书页允许罗马数字，绝不全局硬编码减31。

FTS5分别索引英文词与预分词中文；短词回退限定课程的子串查找，因为默认分词不能代替中文检索。[FTS5说明](https://www.sqlite.org/fts5.html)

事件、幂等结果、作业入队同一事务；模型调用在事务外。KP租约60秒、20秒续租，提交检查递增fence，防止休眠后的旧任务写回。答案始终可收存，冲突只影响后续处理。

**建议：**每日SQLite一致性备份＋blob清单，保留7日/4周；每次发布前实际恢复一次。  
**开放问题：**异机备份位置；只存在同一Mac不算灾备。租约参考`D/learning/storage.py`，新增到期与fence保护。

## 5. AI runtime

**决定：选择服务端结构化状态机。**

`Prepare→Teach→Issue→AwaitAnswer→Grade→Verify→Commit`。追问是旁支；状态转换由服务端控制，避免模型反复调工具消耗额度。备课按对象检索页段，缓存键含资料hash；讲解允许增量文本，题目和评分必须完整验证后发布。

来源四类：原文支持、推导、导师补充、学生陈述；引用只能来自本次材料包。检查页段、逐字引文和范围；来源存在不代表推论正确，出题与严格批改另做语义复核。

rubric发布后冻结；每项得分返回原答字符区间，验证能精确切回原文。短答案如“0”合法，不照搬StudyCoach最少8字符门槛。选择题按服务端私有答案精确评分。依据：`S/packages/ai-runtime/src/{grade-validation,source-validation}.ts`、`D/learning/semantic_assessment.py`。

复核以独立调用、独立上下文完成；影响稳固的评分优先不同后端。不同意则“待复核”，不自动多数表决。掌握更新由纯规则验证，不另请模型裁决。

复用B的CLI传输，在Coach目录独立副本补：实际模型、物理调用数、真流式能力、明确成功/失败终态、禁用隐式兜底选项。原DeepTutor桥不动。Grok单独适配；四家能力逐项实测。只有Python部署或取消机制成为持续负担才改写Kotlin。

**建议：**完整循环常规5次物理调用，另留1次故障恢复；精确判分减少调用。兜底与JSON修复均计预算。讲解60秒、结构化调用90秒；取消杀进程组且不兜底。已出字失败标“不完整”，新尝试另起消息；没有真流式的后端显示等待，不模拟打字。

**开放问题：**真实吞吐与额度[未证实]；预算可调，耗尽保存进度，不循环重试。

## 6. 客户端—服务端协议

**决定：**REST负责命令与快照，WS负责可重放通知，简化离线重试。

```text
GET  /v1/hello
GET  /v1/snapshot?afterSeq=
GET  /v1/events?afterSeq=
GET  /v1/objects/{id}
GET  /v1/blobs/{hash}
POST /v1/commands
POST /v1/turns/{id}/cancel
POST /v1/pair
WS   /v1/stream
```

命令含`id,deviceId,baseRevision,type,payload`；同ID不同载荷返回409。答案outbox先本地落盘，收到持久化ACK才移除。离线测验绑定已下载题目版本；时间、闭卷资格无法核验时只算练习。

```json
{"v":1,"stream":"turn:t1","seq":17,"type":"text.delta",
 "attempt":1,"blockId":"b2","offset":120,"text":"……"}
```

另有`stage、citation、question.ready、grade.ready、completed、failed、cancelled`。账本seq与turn seq分开；客户端仅确认连续序号，缺口补拉；先持久化再发送。过期流返回快照及其边界。网络断开不等于取消任务。参考`D/api/routers/unified_ws.py`。

认证：Ktor与桥仅监听回环；[Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve)提供tailnet HTTPS。首次扫码交换一次性配对码，随后用可撤销设备令牌；服务端只存hash，客户端放系统安全存储，WS不把令牌放URL。

**建议：**`hello`协商协议主次版本、规则版本、能力；不识别的状态事件要求升级，不能静默跳过。  
**开放问题：**当前tailnet HTTPS配置[未证实]。

## 7. 客户端架构与公式

**决定：**

| 模块/依赖 | 选择理由；备选 |
|---|---|
| `contracts/domain` | KMP纯数据与规则，两端同算 |
| `client-data` | SQLDelight类型化SQL；备选Room KMP |
| `client-ui` | Compose Material3＋语义令牌 |
| `androidApp/desktopApp` | 平台入口与富文本适配 |
| `server/ai-bridge/importer` | 部署及数据权限分离 |
| Coroutines、Serialization | StateFlow单向数据流、统一DTO |
| Ktor Client＋OkHttp | Android/JVM均支持WS；备选CIO |
| Lifecycle ViewModel＋Navigation Compose | 按对象ID导航、恢复状态；备选Decompose |
| 构造器注入 | 小项目无需额外DI框架 |

依据：[SQLDelight](https://sqldelight.github.io/sqldelight/2.1.0/multiplatform_sqlite/)、[Ktor引擎](https://ktor.io/docs/client-engines.html)、[共享ViewModel](https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html)。桌面加`coroutines-swing`。

富文本采用**每个学习详情一个文档视图**，避免每条消息一个WebView。Android系统WebView；Mac/后续Windows优先验证KCEF/JCEF，桌面打包依赖不是现成保证。[项目说明](https://github.com/KevinnZou/compose-webview-multiplatform/blob/main/README.desktop.md)

原生导航、输入、题目按钮；文档视图仅承载Markdown/公式。内置Markdown解析器＋KaTeX资源，关闭原始HTML、远程加载及任意JS桥；`trust=false`，输出MathML。[KaTeX选项](https://katex.org/docs/options.html)

增量按稳定blockId更新，50–100ms合批；只重解析未完成尾块。公式闭合前显示未完成文本；长公式局部横滚。用户离底部后停止追滚，以块ID＋块内偏移恢复，不能用绝对像素。

缓存当前课程、近期会话、下载题包、草稿和outbox；不下发测验答案。桌面失败备选JavaFX WebView，代价是另一套兼容验证[未证实]。

**建议：**Gradle Wrapper＋version catalog锁定通过spike的组合，无需全局装Kotlin。已核查JDK17、SDK34/35、build-tools34、sable-api35；SDK位于`/opt/homebrew/share/android-commandlinetools`。缺Wrapper依赖、Compose打包和桌面浏览器运行时验证；JDK17满足桌面打包最低要求。[官方说明](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)

**开放问题：**混合原生/文档视图的中文选词、无障碍和桌面签名必须实测。

## 8. 自适应与折叠屏

**决定：**读取实际窗口dp和姿态，不按机型或像素缩放页面。Android使用窗口尺寸类＋WindowManager折叠信息。[官方指导](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)

| 可用宽度 | 布局 |
|---|---|
| <600dp | 单栏、底部导航；Fold外竖约475 |
| 600–839dp | 导航轨＋单学习栏，路线/资料用抽屉；内竖704、外横751、Pad竖 |
| 840–1199dp | 240dp路线＋学习栏；资料替换路线面板；内横933、部分Pad横 |
| ≥1200dp | 路线240＋学习≥480＋资料320，放不下即退两栏；Mac、大尺寸Pad横 |

可用高度<480dp隐藏非必要导航和装饰。竖向铰链分栏避让；桌面姿态上方阅读、下方输入，两区不足最小可用高度则回单栏。

折叠/旋转保持对象、题目、输入、选区、滚动锚点和订阅游标；网络任务归Repository而非Composable。IME只压缩内容区，输入固定在键盘上方；流式更新不抢焦点。

**建议：**测每个断点±1dp、字体200%、四形态连续切换。  
**开放问题：**设备dp均为估算；真实密度与铰链信息[未证实]。

## 9. 设计系统

**决定：**迁移`S/packages/design-tokens/tokens.css`的语义，不复制Web透明叠层。暖纸`#f4efe6`、正文`#222620`、主按钮深粉`#bd4070`；暗色底`#191712`。通过、稳固、待判同时使用文字和图标，不能只靠颜色。

标题使用经许可打包的中文衬线字体；正文系统无衬线，避免Android缺宋体造成跨端差异。正文16–18sp、行距约1.6；圆角12/18/28dp，动效120/200/320ms，尊重减少动效。

组件：继续卡、路线节点、双层证据徽章、消息块、引用页签、作答卡、批改证据、复习组、日记编辑器、离线条。共用加载/空/错误；学习组件另有流式、待同步、待复核、只读冲突。

**建议：**文字对比度目标4.5:1，触控目标48dp，拒绝直接沿用浅色弱对比文本。  
**开放问题：**衬线字体体积与中文覆盖；未选定前用系统字体，不阻挡闭环。

## 10. DeepTutor → Coach迁移

**决定：**一次性只读导出到Coach暂存库，校验后原子发布；不调用会自动迁移旧库的服务构造器。

| 来源 | Coach目标 |
|---|---|
| `D/services/courses.py`的`courses.json`＋课程库文件 | CourseVersion、Material、关联 |
| `mastery_paths.state_json` | 12模块/49 KP、原ID映射、原始进度 |
| `mastery_topic_sources`＋阅读集 | SourceAnchor、资料集合 |
| `notebook_entries`中的题＋`tools/practice_questions.py`产物 | QuestionVersion、答案私有blob；去重核对108题 |
| `D/services/notebook/service.py`的笔记JSON | Note；保留章节归属 |
| `mastery_interactions/assessment_attempts/mastery_learning_evidence` | 原题、原答、历史评分；交叉去重 |
| `practice_review_state/events`＋路径复习状态 | 同一队列的LegacySchedule |
| `D/services/learning_journal/store.py`日志JSON | 历史日记事实 |
| `sessions/messages/turns/turn_events`、路径会话关联 | 对象对话及历史消息 |

历史“已掌握”标“旧系统通过”，不凭缺字段升级稳固；原有通过仍可用于冲刺续接。无法关联的会话挂课程，不能猜KP。页码逐页保存；教材减31只是该教材的旧规则。

**建议：**幂等键＝源系统＋源ID＋内容hash；复跑零重复，核对数量、引用、原答hash与断点。依据：`D/learning/storage.py`、`D/services/session/sqlite_store.py`。  
**开放问题：**49/108目前是文档基线，现库逐项完整性[未证实]；重命名造成的ID变化需映射报告。

## 11. 测试与验证

**决定：**只证明用户会感受到的正确性。

- 属性测试：重复上传不重复计分；撤销与从头重放一致；离线到达顺序不改变同一最终事件序列的投影；追问不涨掌握；错误答案不因模型自称通过而过关；双设备同题最多一个首做。
- 黄金用例：`.8/.9`边界、三次提示、看答案重试、短答案“0”、评分更正、夏令时、来源缺页、迁移旧通过。
- UI任务：三次点按内开始；半页阅读时流式追加不跳；旋转杀进程后原答仍在；键盘不挡提交；离线重发不多出一次成绩。
- 真实AI端到端：真实CLI＋真实课程页＋真人答案，验讲解、出题、引用、批改、实际模型身份和取消；模拟断网可以，模型回复不能用模板冒充。

**建议：**至少20条代表性真实场景，包含公式、选择题、简答、争议评分、兜底、超时。报告全程首字p50/p95、误判、未判与成本；状态提示不算首字。  
**开放问题：**首字≤5秒作为实测目标，不把旧桥正文阶段4秒当端到端承诺。依据：[改造记录](/Users/zheng_li/DeepTutor/DeepTutor改造记录.md)。

## 12. 里程碑、spike与团队执行

**决定：按风险顺序推进。**

| 顺序 | 交付与判定 |
|---|---|
| M0-A：跨端渲染 | Mac安装包＋Android；50种课程公式、长会话、中文输入；无裁切/丢字，阅读锚点漂移≤一行 |
| M0-B：真实AI | 点击至正文计时；真实模型身份、取消后无孤儿进程、中断不报成功；复核失败不写掌握 |
| M0-C：账本与离线 | 双端并发、断网重传、休眠恢复；零丢答、零重复评分，重放一致 |
| M0-D：迁移演练 | 49点/108题及关联逐项对账，重复导入零新增，备份能恢复 |
| M1：完整闭环 | ECNM10119、六屏、两层证据、复习、日记、离线和四形态；连续7天可只用Coach学习 |
| M2 | 多课程导入、完整模考修复、资料阅读器深化 |
| M3 | Windows打包、通知、性能及无障碍完善 |

M0约一周、M1再3–4周，仅为规划值；不能承诺11月2日前替代DeepTutor。首版不做社区、伙伴、写作、任务看板、向量数据库、全端离线AI、自动改课程图或复杂治理平台。

岗位分工：架构师维护领域/协议及短决策记录；工程主力负责账本、服务端、AI、迁移；前端/安卓负责客户端、渲染与设备验证；验收独立安装、复跑和恢复备份。共享文件明确单一负责人，各自工作树；跨模块先给契约样例，集成按纵向闭环完成，不按代码量宣布完成。

**建议：**每个spike只交付可复跑步骤、实测记录、失败边界与下一决定；代码行数和门禁数量仅报告。  
**开放问题：**桌面富文本、CLI额度、Mac在线率是前三风险；spike失败就缩减呈现或调整实现，不能以占位结果进入M1。