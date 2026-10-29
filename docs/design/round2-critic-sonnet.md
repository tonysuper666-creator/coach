# Coach 第二轮交叉评审（Sonnet 5.5）

三份都已读完（含我自己的）。我亲自核对过的事实标 ✓：订阅桥 `tools/subscription_bridge.py`、`DT/learning/mastery.py`、SC 证据策略/复习队列/ADR-0001、108 题 JSON、`mastery.sqlite3`（只读）、本机工具链、tokens.css 对比度。篇幅：Opus 约 10.5k 汉字、Sonnet 约 6.5k、Astra 约 4.5k（上限 6500；Opus 超 60%，Astra 不是精炼而是省略）。
**总判断：三份都没有推翻已定决定的致命问题。** 最接近致命的是 Astra 把富文本押在桌面 WebView，以及我（Sonnet）的"按设备时间折叠"与"写入时钉死判定"互相矛盾。

## 1 逐节对比

| 节 | 立场（O＝Opus，S＝Sonnet，A＝Astra） | 更站得住 | 理由 |
|---|---|---|---|
| 1 产品闭环 | O：每 KP 恰一条线程；待答时导师每次回复记一次提示。S：同一 Thread 内 teach/ask/feedback/review 四种 turn，ask 缩进。A：Learn/Review/Ask 三种对话，仅"索取答案"记辅助暴露 | S 的 turn 分类＋A 的暴露口径 | S 直指"追问被教学流程打断"这个已知痛点；O 的"回复即提示"会罚澄清性提问（见 §2） |
| 2 学习规则 | O：加权≥0.9 锁存，提示半分；solid 需≥2 条、含≥1 构答、跨 2 日。S：最近 3 次全对，或单次简答复核≥0.8 即过；solid 允许纯选择题。A：同 DT 加权；solid 需两次严格＋1 次非选择题；固定阶梯 1/3/7/14/30，无考试压缩 | O | DT 公式 ✓（mastery.py:17-37，权重 .5…1、1/2 次封顶 .5/.8），O、A 沿用，迁移不掉级；S 的单次简答捷径把门槛交给未验证的 AI 批改；A 的阶梯到考前 4 天会把复习排到考试之后 |
| 3 账本 | O：证据是投影（policyVersion 随出题），对话正文不进账本，按服务端 seq 折叠。S：按 (at, deviceId, deviceSeq) 折叠，证据类别写入时钉死，客户端直接 POST 事件。A：commandId＋effectIndex 幂等，整数分值，ruleVersion 随事件 | O 的投影观＋A 的信封 | 服务端 seq 是唯一无争议的全序；S 的两条主张互相矛盾（§2）；A 的整数分值避免跨端浮点分歧，值得采纳 |
| 4 服务端存储 | O：最完整 DDL，单写者 actor 同事务，租约 TTL120 无 fence。S：事件表加触发器禁改，epoch fencing。A：表清单最简，有 fence、jobs、import_map | O 的 DDL＋S/A 的 fence | Mac 休眠恢复后，旧 CLI 回合仍会回写——正是 fence 要防的；O 缺这一条 |
| 5 AI runtime | 三者都选服务端状态机。O：任务表、预生成题池、异厂复核、桥 8791 副本。S：盲复核（复核者不看 key）、植入缺陷测试、来源标签流式过滤，但直连 8790。A：唯一审计桥缺陷：无 Grok、仅 Claude 非 JSON 真流式、兜底后 model 仍报请求值、中途异常后仍发结束标记 | O 的任务表为骨架 | A 的缺陷四条全部核实 ✓（MODELS 仅 claude/agy/codex；live_stream_response 异常后仍 yield finish 与 [DONE]；兜底 chunk 的 model＝请求值）；S 的盲复核最能抓"永远同意的假复核"；A 没有预生成，出题要同步等（§2） |
| 6 协议 | O：/commands 批量、具体 WS 帧、msg.snapshot。S：客户端 POST /events、topic 订阅。A：端点最少，帧含 attempt/blockId/offset | O（命令制） | 业务校验集中在服务端命令层；S 让客户端直写事件，校验要逐类型补。认证三者一致（回环＋Tailscale＋设备令牌） |
| 7 客户端与公式 | O：原生 Markdown＋服务端 MathJax→SVG＋coil-svg。S：同源，但自绘 MathSvgPainter，有源码降级阶梯。A：每个学习详情一个 WebView 文档视图（Android 系统 WebView，桌面 KCEF/JCEF）＋KaTeX | O/S 路线；降级阶梯取 S | A 把最大风险押在首发平台 Mac 的最弱环节，且没说明题卡如何插入文档流；O/S 两端同一代码路径、暗色不必重渲染 |
| 8 折叠屏 | O：短高(<480)优先、桌面/书本姿态、IME 组字不发送。S：按优先级加窗格（对话>引用>路线），movableContentOf 保状态。A：标准四档＋避让铰链 | S 的窗格规则＋O 的短高/IME | S 的规则是可单测的算法；O 的"组字期回车永不发送"是中文用户必需项 |
| 9 设计系统 | O/S 都实算对比度，fg-tertiary 2.53 不过 ✓（白字/主按钮 5.08 ✓）。A 只写"目标 4.5:1"，未发现此问题 | O（含暗色 tertiary 修正 #8f8a80≈4.6 ✓） | 已用数字证明 |
| 10 迁移 | O/S 查了真实数据：108＝60 单选＋48 简答 ✓，tags 只有章、没有 KP ✓，62-KP 路径 0 作答。A 称 49/108 为"文档基线"，未发现题无 KP 归属，也未排除 `library/…/答案/` ✓目录存在 | S 的归属三步＋O 的对账清单 | A 的"复习队列含题库"在 108 题无 KP 时无法落地 |
| 11 测试 | O：属性＋黄金＋liveTest（注入 5 道错键、pgrep 验取消）。S：P1–P7、真实 turn 回放、植入缺陷。A：20 条真实场景 | O＋S 合并 | 都避开记账式断言；S 的 P1 需改写（§2） |
| 12 里程碑 | O：6 个 spike，S2 最先；目录所有权最细。S：S0 工具链先行。A：M0-A…D | O 的分工表＋S 的 S0 | 工具链不通则全部阻塞，应在渲染 spike 之前 |

## 2 每份设计的缺陷

**Opus**
- [重要] 待答题时"导师每次回复＝一次提示"，且任一提示该次只得半分：问一句术语就等于做错，3 连对门槛下几乎不可能通过。与"限制只为保护用户"相悖。
- [重要] 对话正文不进账本：多一条同步通道（messages 单独补齐）；`events.jsonl` 重建不含对话。
- [重要] 折叠序＝服务端 seq：离线晚到的作答排到后面，"最近 3 次"窗口顺序偏离真实时间。应在证据投影内按 occurredAt 排序。
- [重要] 租约无 fence（见 §1）。
- [重要] 公式 SVG 只做 LRU 20MB、无源码降级：离线翻旧消息，被淘汰的公式变空白。
- [重要] 把 coil-svg 渲染 MathJax 输出（use/defs/`<text>` 中文）和 Placeholder 基线对齐写成"决定"，自己又标 [未证实]；S2 的失败分支直接跳到 WebView，漏了"改自绘"这一级。
- [次要] 篇幅超限 60%；桥内与 Coach 双层兜底（25 秒主动断开，桥内另有出错即兜底）。

**Sonnet（我）**
- [重要，实现首日会爆] 折叠序 (at, deviceId, deviceSeq) 与"EvidenceRecorded.decision 写入时钉死"互相矛盾：迟到事件改变早先判定的前提（首次见题、attempt==1、14 天窗），P1"任意到达顺序结果相同"会直接红。设备时钟错误还会让事件错位。
- [重要] 不做投影缓存＋按 at 排序，增量 fold 不可行。SC 在不到 5 万事件时 p95 已 9.66 秒 ✓（ADR-0001；主因是 IPC 序列化，但说明"纯 fold 够用"必须实测）。
- [重要] "任一简答首答复核≥0.8 即 passed"：单样本、依赖未验证的批改；我没设批改质量 spike（Opus 有 S6）。
- [重要] solid 允许纯选择题累积：5 选 1 猜中 20%，两题仅 4%，"稳固"名不副实。
- [重要] 写"用 8790 的 BridgeProvider"，又要"改桥副本加 served-model 头"，自相矛盾；直连现役桥还在期中周抢 Claude 的 3 个槽位。
- [重要] S1 同时证伪自绘 Painter、normalizer、增量分块、Markdown 渲染、ULID 五件事，一周未必够；退路 +1–2 周没进排期。
- [重要] 离线"未见题 key 不在设备上"过度保护，手机上没有即时反馈（§5）。
- [次要] 客户端直写事件；篇幅恰卡 6497 字，靠压缩换来，难读。

**Astra**
- [重要，接近致命] 同步出题：Issue 步骤"完整验证后发布"，每点一次"出题"要等一次 JSON 调用加复核，30–60 秒；无预生成题池。违背立项方案 §八"≤3 次点按、首字≤5 秒"。
- [重要] 桌面 KCEF/JCEF：运行时体积、首启下载、公证、macOS 27 beta 兼容均 [未证实]；备选 JavaFX WebView 是第二套兼容面。
- [重要] 整段会话进一个 WebView，却保留原生题卡/作答卡：穿插位置、嵌套滚动、选区、IME、折叠重排都没设计。KaTeX 只输出 MathML 时，Android 对复杂公式的渲染与字体 [未证实]。
- [重要] 无考试日间隔压缩；迁移漏掉"108 题无 KP"和排除答案目录。
- [重要] 信封臃肿：courseVersionId、CourseVersion 一等对象、ruleVersion、zoneId、localDate 全带。重新引入 SC 版本化重量，违背"不要治理过重"。
- [重要] 租约协议、命令类型、DTO、离线包内容、WS 订阅主题只有一行或缺失，"能直接开工"不足。
- [次要] 稳固要求"未见变体非选择题"，却没有生成题池与复核流水线来供给。

## 3 共识（可直接采纳）

1. 服务端驱动状态机，不用 tool-calling；模型只产出内容，掌握更新由纯规则完成。
2. 一本账、纯函数投影；事件带 schema 版本，未知事件保留；投影版本变化即重放。
3. 选择题服务端精确判分；简答 rubric 冻结＋逐字证据＋异厂复核；复核不过不入账（fail closed）。
4. 已通过与稳固两层，稳固⇒通过；前置依赖是软提示；复习只改日程不改"已通过"。
5. 生产无 mock；AI 失败显示真实原因与真实模型名；离线不虚构 AI 回复。
6. 教材页：PDF 页＋书页逐页保存；FTS 英文与中文分表。
7. 认证：回环/Tailscale＋配对码换可吊销设备令牌（只存 hash）。
8. 取消杀整个进程组；已出字失败标"截断"，不自动兜底；无真流式的后端显示等待，不模拟打字。
9. 订阅桥先用副本、原桥不动；替换时机由 Grok 接入或取消机制负担触发。
10. 迁移只读、幂等、期中后正式跑；M1 赶不上 11-02，期中继续用 DeepTutor。
11. 技术选型：SQLDelight、Ktor、navigation-compose、ViewModel＋StateFlow、手写 DI。

## 4 分歧与我的倾向

| # | 分歧 | 倾向 |
|---|---|---|
| D1 | 公式：WebView 文档视图(A) vs MathJax→SVG 原生(O/S) | SVG。S1 让 coil-svg 与自绘同台，WebView 只做 Android 的块级退路 |
| D2 | 折叠序/入口：seq＋命令(O/A) vs at＋客户端事件(S) | seq＋命令。证据窗口在投影内按 occurredAt 排序；"首次/重复/attempt"由投影重算，不存进事件 |
| D3 | passed 判据与锁存 | O 的加权公式；锁存，一次失手只标"需回炉"（A 允许降级，会重现 10-28"答多少遍都过不了"）；否决 S 的单次简答捷径 |
| D4 | solid 是否允许纯选择题 | 不允许（O、A）。构答或限时/模考单选才合格 |
| D5 | 阶段机：A 保留为视图，O/S 不做 | 不做。模考首做/修复双轨留 M2 |
| D6 | 追问算不算提示：O 每次回复记；A 仅索取答案；S 未定 | 仅"泄露"才计：选择题用确定性子串检测（回复含正确选项字母/文本），简答在待答时默认记 1 级并事先提示，"澄清题意"按钮不计 |
| D7 | 复核：O 给 key；S 盲复核；A 独立上下文 | 出题与选择题 key 用盲复核；批改复核给固定 rubric |
| D8 | 复习种子：O 1 天；S 按强度 7/3/1；A 阶梯 | 用 SC 的 ease 模型（review-queue.ts ✓）＋考试截断；种子是参数，两周后按体感调 |
| D9 | 页码：逐页存(O/A) vs offset 推导(S) | 逐页存。offset 只在导入时校验用 |
| D10 | "继续"是否被到期复习抢占：O 不抢；S 到期≥5 项且逾期≥1 天先复习 | S（冲刺期复习不能无限后延） |
| D11 | HTTPS：tailscale serve(S/A) vs 直绑 Tailscale IP＋明文例外(O) | serve；tailnet 开不了 HTTPS 再退 O。S0 验证 |

## 5 三者都漏掉的

1. **离线即时反馈。** Mac 小时在线率 11–38%（立项方案 §七.4），手机碎片场景多半在离线。三份的离线作答都要等 Mac 回来才知对错。建议 commit-then-reveal：先把作答落本地 outbox，再用包内 key 本地判选择题，立刻反馈；账本仍以服务端复核结果为准。key 在单用户设备上只影响自己，首答纯度由"先记后揭"保证。
2. **服务端回档后的游标。** 从旧备份恢复后，客户端持有的 seq 超前于服务端。需要 `ledgerEpoch`：hello 里发现纪元变化即强制重同步，并保留客户端未确认的命令。
3. **订阅掉登录的用户路径。** CLI 登录需在 Mac 上交互完成，手机无法处理。需定义：健康检查如何探测、手机上显示什么、谁来重新登录。
4. **额度可见性。** 只有 Opus 有后台 60 次/天上限。没有任何一份设计用户可见的用量与降级策略，而订阅 CLI 是否提供额度查询 [未证实]。
5. **分发。** APK 的签名与更新（未知来源安装）、Mac 应用的开发者签名/公证（macOS 27 beta）——谁签、怎么装，都没写。
6. **输入侧的公式。** 计算题答案在手机键盘上怎么写，三份只处理渲染。ECNM10119 期中 58% 是 Essay＋Analytical（Opus 引课程配置），至少要定"纯文本约定"，并让批改接受它。

## 6 整合建议

**按节采纳**
- §1：S 的 turn 分类、O 的屏幕表与离线横幅、D6 的提示口径。
- §2：O 的两层公式与锁存；S 的"导航失败放行、证据失败关闭"和对题量的警觉（108 题≈2.2/KP，passed 必须靠生成题＋盲复核）；复习用 SC 的 ease＋考试截断；阶段不做。
- §3：信封用 A 的（commandId＋effectIndex、整数分），去掉 courseVersionId/ruleVersion 逐事件字段；证据＝投影（O）；折叠序 seq；事件清单以 O 为底，补 S 的 ReviewScheduleSeeded/UserOverride/ExamSet。P1 改为"同一事件集合，任意到达顺序，结果相同"。
- §4：O 的 DDL；加 S/A 的 fence 与 `ledgerEpoch`；备份的 jsonl 含 messages。
- §5：O 的任务表＋预生成；S 的盲复核＋植入缺陷测试＋来源标签流式过滤；A 的四条桥缺陷作副本的验收项；桥 8791。
- §6：O 的端点与帧；加 A 的 attempt/blockId；认证同 D11。
- §7：先试 O 的 coil-svg，再自绘（S）；降级阶梯用 S；工具链现状用 S 的实测（JDK17、SDK34/35、AVD sable-api35 ✓；gradle/kotlin/adb 不在 PATH ✓）。
- §8：S 的窗格算法加 O 的短高与 IME；半折叠姿态留 M3。
- §10：S 的归属三步（章→模块；页码重叠；AI 选并盲复核）＋O 的对账清单；`答案/` 与 sealed 不进索引与导师上下文。
- §12：S0 先行；O 的目录所有权表，`acceptance/` 只有验收能改。

**M1 范围（最小闭环）**：ECNM10119；安卓＋Mac；垂直切片顺序：账本与投影→复习（最不依赖 AI）→练习与判分→讲解流→今天页→日记。含：两层掌握、统一复习（含考试截断）、预生成题池与异厂复核、公式 SVG＋降级、离线包（含 commit-then-reveal）、配对、迁移 dry-run（期中后正式跑）、四形态。不含：阶段/模考、Windows、导入界面、通知、深查原文、Grok、前置编辑器。出口：立项 §八三条。

**先做的 spike**（S0 半天前置：wrapper＋空壳同跑 sable-api35 与 Mac 桌面；两端建 FTS5 与 trigram 表；Mac 上中文组字回车）
1. **渲染**：语料＝DeepTutor 真实 turn（`chat_history.db` 的 3318 条 turn_events ✓，取 20 条含公式）＋10 条最坏 LaTeX＋含中文 `\text`。判定：≥98% 与 MathJax 参考一致，其余降级为源码且不空白；内屏竖 1500 字含 6 公式流式，丢帧≤3%，已封口块位移＝0，闭合到可见≤300ms；Android 与 Mac 都过。失败则块级 WebView＋KaTeX。
2. **真实 AI 链路**：Ktor→桥副本，20 条真实大小提示。判定：≤40K 字首字 p50≤5 秒、p90≤8 秒；取消后 2 秒内无残留 CLI 进程；兜底时 served_by 正确；中途异常客户端收到 error 终态而非 done；注入 5 道错键题，盲复核全拦；在非学习时段跑，不挤占期中周额度。
3. **账本＋离线＋回档**：10k 随机用例，同一事件集合任意到达顺序（含重复、晚到）投影一致；5 万事件冷折叠耗时在 Android 上报告，超过 800ms（SC ADR 的阈值）才引入增量快照；断网答 5 题、重启服务、恢复旧备份后客户端检测到纪元变化并重同步，零丢答、零重复。

## 7 待核实清单

| 事实 | 怎么核实 |
|---|---|
| Android 系统 SQLite 与 sqlite-jdbc 是否含 FTS5 与 trigram（需 ≥3.34） | sable-api35 与 JVM 各跑 `select sqlite_version()` 并建 `fts5(tokenize='trigram')` 表 |
| coil-svg/Skia 能否渲染 MathJax SVG（use/defs、currentColor、`<text>` 中文） | mathd 出 30 条样例，Android 与桌面截图对比 |
| Compose 行内 Placeholder 的基线对齐能否做到与正文偏差≤1dp | 含分式、上下标的行内公式做截图差分 |
| Compose Desktop 与 KCEF 在 macOS 27 beta 上能否运行、IME 组字是否正常 | S0 空壳手动输入中文；KCEF 仅在 SVG 路线失败时再测 |
| 纯 fold 5 万事件在 Android 的冷启动耗时 | spike 3 |
| Codex 兜底（非流式）的实际等待 | 读 bridge.log 中 `ok(兜底` 行的耗时分布 |
| tailnet 能否开 HTTPS | 用 `/Applications/Tailscale.app` 内置 CLI 看 MagicDNS 与 HTTPS 状态，或查管理控制台 |
| Fold8/Pad Mini 的真实窗口 dp 与铰链信息（三份都是估算） | 真机打印 WindowMetrics 与 FoldingFeature |
| 108 题 KP 归属的自动可得率；17 条证据与 20 条交互的差异 | 迁移 dry-run 统计；逐条对 `mastery_interactions` 与 `mastery_learning_evidence` 的 id |
| 把 Claude/Codex 订阅 CLI 作为应用后端的条款与风控 | 联网查两家当前使用条款；Antigravity 另有已知风险 |
| 订阅额度能否支撑每循环约 5 次调用＋后台预生成 | 跑一周，汇总 `ai_calls`，与桥日志对照 |
| MathJax 单公式渲染耗时与常驻内存（Opus 估"几毫秒"） | mathd 压测 100 条 |
