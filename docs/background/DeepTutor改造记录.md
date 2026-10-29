# DeepTutor 改造记录（截至 2026-10-29）

用途：记下我们对 DeepTutor 1.6.15（港大开源 AI 导师，装在 `~/DeepTutor`）做过的全部改动，供之后评估是否重启 StudyCoach 时对照。
对象：学 ECNM10119 健康经济学（爱丁堡大四课），期中 2026-11-02 14:25。
原则：**每个改动都写了为什么、改在哪、怎么还原、升级后要不要重做。** 带 ⚠ 的是改了第三方包源码，升级 DeepTutor 后会丢。

## 一、总览（一页版）

| # | 改动 | 解决什么 | 位置 | 升级后 |
|---|---|---|---|---|
| 1 | 订阅转接桥 | DeepTutor 不支持 Claude / Antigravity / Codex 订阅，只认 API Key | `tools/subscription_bridge.py`（127.0.0.1:8790） | 不受影响 |
| 2 | 桥：被拦截/超时自动兜底 | Claude Code 安全拦截会误伤出题和对话 | 同上，兜底现为 Codex gpt-6.1-sol | 不受影响 |
| 3 | 桥：真流式 + 低推理强度 | 回答要等整段写完 20–35 秒才出现 | 同上 | 不受影响 |
| 4 | 四位 CLI 导师 + 沙箱 | 让导师读原文，但不让它读到答案或私人目录 | `tools/sandboxed_agent.sh`、`agent_sandbox.sb`、`bin/` | 不受影响 |
| 5 | 课程学习空间 | 课程、掌握路径、阅读集、笔记本、108 题、复习书 | 默认工作区；`tools/*.py` 可重复生成 | 数据在 data/ |
| 6 ⚠ | 站点包补丁（6 处） | 掌握评估被拒、压缩每轮必败 | `tools/patch_deeptutor.py` | 启动脚本自动重打，找不到锚点会报错 |
| 7 | 定性门改选择题门 | 选择题答多少遍都过不了 | `tools/relax_gates.py` | 重建路径后要重跑 |
| 8 | 掌握路径大纲三次重排 | 太碎→真题导向→范围优先 | `merge_outline.py`、`restructure_outline.py`、`restore_scope.py` | 结果在 sqlite |
| 9 | 局域网 / Tailscale 访问 | 手机平板用 | `tools/bind_lan.sh`、Tailscale 地址 | ⚠ 改了 launcher.py，升级后重跑 |
| 10 | 手机入口 3790 | 浏览器标签栏/地址栏占屏 | `tools/mobile_proxy.py` | 不受影响 |
| 11 | 安卓 App | 不想再用浏览器 | `android/`（`build.sh`） | 不受影响 |
| 12 | 启动脚本 | 一键起所有部件 | `启动DeepTutor.command` | — |

## 二、模型接入：订阅转接桥

- **问题**：DeepTutor 只会调 API；Claude / Antigravity / Codex 都是订阅登录的 CLI。他要求生成资料用 Sonnet 或 Argon，不用本地模型，也不用 deepseek-pro。
- **做法**：自写 OpenAI 兼容服务，把请求转成 CLI 调用：
  - `claude-sonnet-5-5` → `claude -p`（提示走 stdin；`--tools "" --strict-mcp-config --setting-sources ""`；不能用 `--bare`，它不读订阅登录）。
  - `gemini-4-argon-high/medium` → 沙箱里的 `agy -p`。
  - `gpt-6.1-sol` → 沙箱里的 `codex exec`（提示走 stdin，答案取 `-o` 文件）。
  - 工具调用靠提示协议 `<tool_calls>[…]</tool_calls>`；也认 XML 写法；模型先说一句再调用时，那句话作为 content 一起返回。
- **模型目录**：默认对话模型 = Sonnet（桥）；后台小任务 = deepseek-v4.1-flash（CodeBuddy）；「阅读练习题」等单项覆盖 = Sonnet。
- **兜底**：Claude 被安全拦截（`can't respond to this message`，如 `[reasoning_extraction]`）或超时、出错，自动改用兜底模型重试一次。原兜底是 Argon，**10-29 改为 Codex gpt-6.1-sol**（对比后 Argon 更慢更啰嗦）。兜底也超时再试一次。
- **并发**：Claude 3、Antigravity 2、Codex 2。
- **日志**：`data/user/runtime/bridge.log`，一行一个请求，只记长度和耗时，不记提示词和答案。流式调用多记 `ttft=`（首字秒数）、`ot=`（输出 token）、`raw=`（原始输出字数）。
- **操作**：`tools/start_bridge.sh [stop]`；screen 会话 `dtbridge`；从我的会话里启动一律用 `env -i`。测试新版另起一份用 `BRIDGE_PORT=8791`。
- **备份**：`subscription_bridge.py.bak-20261028 / -prestream / -prefallback`。

## 三、提速（10-29）

- **真流式**：以前桥等 Claude 整段写完才切块发出；现在 `claude -p --output-format stream-json --include-partial-messages` 边收边转。协议里「整条回复就是一个工具调用块」，所以正文先发，遇到 `<tool_call` 开头的部分扣住，结束后解析成工具调用。出错且还没出字时照旧走兜底；出字之后出错只能截断。客户端断开会杀掉 CLI 进程，已验证无残留。
- **推理强度默认 low**：同样 5 题合计快约 25%，答对率没变；`BRIDGE_CLAUDE_EFFORT=""` 恢复默认；`BRIDGE_CODEX_EFFORT` 控制 Codex（默认 high）。样本小，没做大规模质量对比。
- **压缩失败的真因**（⚠ 站点包补丁 4–6）：`capabilities/mastery/memory.py` 校验过严，模型漏写或多写一个存档编号，整条摘要作废，每轮白跑一次摘要调用，下一轮又重来。改成按实际批次修正。补丁后日志里不再出现 `Context condensation failed`。
- **实测**：写长回答的那一步，首字约 4 秒（以前 20–35 秒）。
- **没解决的**：每轮开头还有一次约 110K 字的调用，返回的是工具调用，不是文字，要 14–24 秒，界面上看不见，所以总等待仍有 20 多秒。合成请求复现不了（只要 5–7 秒，缓存正常命中），真正的原因没确定；已加 `ot=`、`raw=` 日志，下一步根据它判断。
- **模型对比**见 `模型对比_2026-10-29.md`：Claude Sonnet 5.5 最优；Codex gpt-6.1-sol 最严谨但推理题 35–50 秒；Gemini 3.8 Flash 与 Argon 更慢更啰嗦；CodeBuddy DeepSeek-V4.1-Flash 会自己调写文件工具，不适合当导师。

## 四、导师与沙箱

- **四位导师**（subagent 连接，cwd 都是学习库）：Claude 导师（固定 sonnet 5.5）、Codex 导师、Grok 导师、Antigravity 导师。每轮由他在输入框「资源 → 询问子智能体」里选，主模型不能自己切换。
- **沙箱**：`bin/{codex,grok,agy}` → `tools/sandboxed_agent.sh` → `sandbox-exec -f tools/agent_sandbox.sb`。**主目录白名单**：只读学习库，读写各 CLI 自己的配置目录，其余（Documents、Downloads、iCloud、sealed）全拒。起因：第一版黑名单沙箱下 Codex 扫过整个主目录。CLI 自带沙箱不能嵌套，所以 Codex 设 `danger-full-access` 并关掉 web search。
- **封存**：KEY 答案、tutorial 讲解、《07-练习题与答案》放 `~/DeepTutor/sealed/`，不给导师看。
- **agy**：`~/.gemini/antigravity-cli/settings.json` 写了 `permissions.allow`（rg/grep/cat/ls/head/tail/wc），否则无界面模式遇到审批整轮为空。Antigravity 条款有封号风险，是他明确要求才接的。
- **Grok** 从 1.0.52 升到 1.0.70；`--no-memory` 在包装脚本里补进 `--help` 才能通过 DeepTutor 的检测。

## 五、课程内容

- **课程库**：`tools/build_ecnm10119.py` 从 Obsidian 知识库生成 `library/`：教材 PDF 转 md 并按 24 章切开，每页加「PDF 第 N 页 · 书第 M 页」标记（书页 = PDF 页 − 31）；写 `AGENTS.md`（导师规则）。**没有向量模型**（订阅里没有 embedding），直接用文本搜索。
- **学习空间全部放默认工作区**：这版界面的课程列表、笔记本页只看默认工作区。
- **资源**：课程 `course_8f01bd033655`；阅读集 13 份；笔记本「章节精要」；108 道原创练习题；复习书 6 章；任务看板 7 张；人格 `health-econ-tutor`；DeepTutor 记忆 L3/preferences 已改（优先带他去课程资源、选了导师就用 consult_subagent 核对原文）。
- **生成脚本**：`chapter_digests.py`、`import_digests.py`、`practice_questions.py`、`mastery_outline.py`、`setup_course_space.py`（可重复运行）；`更新课程资料.command` 刷新课程资料。

## 六、站点包补丁（⚠ 升级 DeepTutor 后会丢）

`tools/patch_deeptutor.py`，幂等，原文件备份在 `tools/patch_backups/*.orig`，已接进启动脚本，找不到锚点会返回非零并提示。

1. `learning/service.py`：`mastery_assess` 校验证据时允许「聚焦范围」的证据（`allow_partial=True`）。原来每次都被「This packet supports a teaching slice…」拒绝，模型重试 8 次耗尽探索轮次。
2. `services/session/context_builder.py`：禁用上下文压缩的「回放对话+工具列表」分支（每轮烧 30–60 秒后必抛错），改走纯文本摘要。
3. 同文件：压缩失败日志加堆栈（launcher.log 里能看到 Traceback）。
4. `capabilities/mastery/memory.py`：覆盖 ID 与实际批次不一致时按实际批次修正。
5. 同文件：笔记引用了不存在的存档时，过滤掉不存在的 ID 而不是报错。
6. 同文件：没被任何笔记引用的批次，挂到第一条笔记上。

另有一处 ⚠：`tools/bind_lan.sh` 改了 launcher.py 的绑定，让前端监听 0.0.0.0（后端仍只听 127.0.0.1，浏览器只走前端的相对路径）。想只本机可用就跑 `bind_localhost.sh`。

## 七、掌握路径的改造

- **定性门**：掌握路径里 `concept` / `design` 类知识点要「用自己的话解释」后 `mastery_assess` 才开门。他说「太累了换种方法考我」，导师就一直出选择题，答多少遍都不推进。→ `relax_gates.py` 把全部 46 个改成 `memory`（选择题答对率 ≥0.9 即过关），原类型存 `gate_types_before.json`。其中原 design 的 9 个名称前加「【Essay】」，列在 `Essay专项训练清单.md`，之后单独训练。
- **大纲三次调整**：
  1. 10-28 嫌太碎：`merge_outline.py` 17 模块 93 个 → 14 模块 48 个（只并不删）。
  2. 10-29 按真题考点分三档：`restructure_outline.py` → 12 模块 42 个。
  3. 他说不必只为真题服务：`restore_scope.py` 全部放回 → **12 模块 49 个**（现状）。补了医院趋势（Hill-Burton）、本地医院市场结构、Goldman & Smith、Akerlof 系数变体。
- **原则**：范围内（Week 1–6 的 slides、习题、必读）都要讲清楚，别按「真题考不考」砍。
- **一个坑**：改知识点名称会让它重新生成 ID（按名称片段定位，别按旧 ID 前缀）；名称不变的知识点掌握度保留。
- **备份**：`tools/patch_backups/mastery.sqlite3.*`（relax、merge、restructure、restore 前各一份）。

## 八、手机与安卓

- 10-29 修复：精通目标概览页因知识点名称很长（nowrap）把页面撑到约 2500px 宽导致显示混乱，手机入口脚本在该页让名称换行。
- 手机布局：⇔ 按钮切换「桌面布局」（viewport 宽 1100，≥lg 断点，会话页左侧出现可展开/折叠的大纲）；概览页大纲与「精通会话」卡重叠的问题也已用 CSS 修掉。App v1.1 开了 wide viewport（versionCode 2）。
- **机型适配（10-29）**：他的手机是三星 Galaxy Z Fold8（外屏 5.5" 1248×1972，10:16；内屏 7.6" 1848×2448，4:3，403ppi），另有 OPPO Pad Mini（8.8" 2520×1680，3:2，144Hz）。按 Samsung 常见 420dpi 估算：外屏竖约 475dp 宽，外屏横约 751dp，内屏竖约 704dp，内屏横约 933dp；Pad Mini 竖约 750–840dp，横约 1120–1260dp（具体 dp 受系统「显示缩放」影响，App 读真实值）。
- **自动布局**：App 通过 `Android.widthDp()` 报告窗口宽度，折叠/展开/旋转时 `onConfigurationChanged` 通知页面。规则：<600dp 用手机布局；600–1024dp 用桌面布局（viewport 宽 1024，缩放到刚好放下）；≥1024dp 原生桌面布局。（10-29 晚他要求只留自动：已去掉「A/桌/手」手动切换按钮；另加了去回弹、去点击高亮、去 300ms 点击延迟、WebView 底色与页面一致。App v1.5）App v1.4：内容铺满整屏（含摄像头缺口），键盘弹出时只让出键盘高度。模拟器里用 1848×2448 / 2448×1848 + 420dpi 验证过内屏竖/横。
- **公式显示**：概览页「精通会话」卡的预览是原始 markdown/LaTeX（`$$NPV=\sum…$$`），脚本把它清成可读文字（希腊字母、Σ 等转符号）；聊天正文里的公式本来就有渲染，没动。
- 注意缓存：上游页面带 ETag，手机会拿到 304 一直用旧缓存，所以手机入口对 HTML 去掉条件请求头并返回 no-store，否则改了注入脚本手机看不到。

- **访问**：局域网 192.168.1.247 在他的设备上打不开（疑似路由器隔离或 VPN 抢线路），改用 Tailscale 地址 `100.88.12.89`。
- **手机入口 3790**（`tools/mobile_proxy.py`，screen `dtmobile`，随启动脚本起）：转发 3782，HTML 里注入脚本：点一下进全屏、会话页去掉顶部栏、左边缘 ⛶ / ☰ 两个小按钮。`/app.apk` 提供安装包。
- **安卓 App**（`android/`，包名 `local.deeptutor`）：全屏 WebView，先连 Tailscale 地址，连不上试局域网，都不行显示重试页；屏幕常亮、返回键后退、支持上传附件。`build.sh` 不用 Gradle，用 aapt2 + javac + d8 + apksigner，用 `~/.android/debug.keystore` 签名（覆盖安装要同一把钥匙）。在模拟器 sable-api35 上验证过；真机装机后的体验未逐项验证。

## 九、启动与运维

- `启动DeepTutor.command` 依次：LAN 绑定 → 站点包补丁 → 订阅桥 → 手机入口 → 停旧起新（后端卡死时强杀）→ 打开浏览器。Windows 访问走 `tunnel_to_windows.sh`（ssh -R 反向隧道，screen `dtwin`）。
- 注意：后端 `deeptutor stop` 返回时旧进程可能还没退干净，需要等；我手动重启时试过一次起不来，需再 `deeptutor start --detach --no-browser`。
- 日志：`data/user/runtime/{bridge.log,launcher.log}`、`data/user/logs/deeptutor.jsonl`。

## 十、已知问题与风险

1. 每轮开头的隐形工具调用 14–24 秒，原因未定（见第三节）。
2. 站点包补丁依赖源码锚点，DeepTutor 升级可能让补丁失效或冲突。
3. 低推理强度对出题答案正确性的影响只测了 5 题，没有大样本。
4. 掌握路径靠「选择题答对率」判定，不再检查「用自己的话解释」；essay 能力要另外练。
5. Mac 休眠或重启后所有部件都要重新启动；手机侧要开 Tailscale。
6. 订阅型 CLI 受各家额度和风控影响（Claude 偶尔掉登录、Antigravity 条款风险）。

## 十一、给「是否重启 StudyCoach」的参考（我的判断，待评估）

- **可直接复用的思路**：订阅桥（不依赖 API Key）、真流式、兜底链、沙箱白名单、封存答案、选择题门 + essay 另练、按范围而非按真题组织大纲。
- **DeepTutor 的代价**：要靠 6 处站点包补丁才顺手；每轮调用上下文很大（约 11 万字）、工具轮次多；升级有风险。
- **StudyCoach 的优势要看**：它是自己写的，没有这些外来约束；但课程库、掌握路径、题库、复习书这些内容层，现在都已经在 DeepTutor 里做好了，可导出复用（`library/`、`source/`、`tools/*.py` 生成脚本）。
- 建议评估时先回答：是要一个更轻的应用，还是要继续沿用 DeepTutor 的掌握路径引擎？

- **大纲面板（10-29 晚）**：DeepTutor 的会话页左侧大纲栏用 `xl:block`（≥1280px 才显示）。手机桌面布局宽 1024，原本看不到，所以注入 CSS 在 1000–1279px 且在会话页时强制显示它（栏内有 « 可折叠）。

## 十二、原生应用评估（10-29）

详见 `原生应用评估.md`。结论：不建议期中前动架构；期中后优先做「StudyCoach 令牌 + 只含在用功能的轻量 React 前端，连 DeepTutor 后端，套现有安卓壳」（MVP 3–5 天）；真原生 Compose 为 MVP 2–3 周、打磨 4–6 周，最难是公式渲染与协议维护，折叠屏适配是它的优势。StudyCoach 可借：设计令牌（暖纸色 #f4efe6、粉色主色、衬线标题、大圆角）、块状路线与学习日记思路；不借它的后端。

## 十三、StudyCoach × DeepTutor 深度对比与结合方案（10-29）

详见 `StudyCoach与DeepTutor深度对比与结合方案.md`。结论：DeepTutor 引擎强但入口并列、上下文要手选（对话不属于任何学习对象；掌握度只在精通会话写；两套复习互不相通；我们 108 题的复习状态为 0）；StudyCoach 有单一“今天”入口和严格证据规则，但 Web 版没接 AI、完整闭环只在冻结的桌面版。方案：不合并代码库，DeepTutor 当引擎，新增很薄的 Coach Gateway（今天页、对话自动绑定、统一复习队列、日记、证据分层）+ StudyCoach 设计语言前端，放进安卓壳。分期 P1–P4，期中（11-02）前不动，待他拍板三件事：入口唯一化、P1 范围、开工时机。

## 十四、大纲按「分值 + 依赖 + 完整性」重排（10-29 晚）
`tools/reorder_by_value.py`：只换模块顺序，不增删、不改名知识点，掌握度保留。新顺序见 `期中分值覆盖表.md` 末尾。依据：真题只当分值线索，同时保持承上启下（保险基础→RS；实验设计→道德风险/RAND；Grossman→社会经济差异）和课程完整性。同时撤回了桥的低推理强度默认。

## 十五、让 DeepTutor 现在就更好用（10-29 夜）
起因：他反映导师「变傻、答非所问」。做了四件事（都是配置/内容层，没动源码）：
1. **说明书去陈旧 + 答所问优先**：persona `health-econ-tutor`（经 API 更新）、课程说明、AGENTS.md、工作区说明里删掉「17 段、93 个知识点、10-28 从零开始、旧每日计划」，换成 12 模块 49 点的新顺序；新增「他问什么先答什么，答完一句话问是否回到当前知识点，不要每次以出题收尾；问进度按分值回答；问真题就检索全文并完整引用；他说快就压缩」。备份 `tools/course_space/*.bak-20261029`。
2. **把考点分值放进资料库**：`笔记/期中复习/14-期中分值覆盖表.md`（含往年期末卷考点），已重建索引；导师现在能按分值回答「学了多少」。
3. **去掉每轮 30 秒的记忆摘要**：Sonnet 在模型目录里没有写上下文窗口，DeepTutor 按 64K 算，历史预算只有约 23K token，对话一长每轮都要再压缩一次（每次约 30 秒，用摘要顶替原话，也是「变傻」的嫌疑）。给 `claude-sonnet-5-5` 写上 `context_window`（先写了 20 万，他要求按真实值：CLI 的 usage 报告里 Sonnet 5.5 的 contextWindow=1,000,000、最大输出 128,000，现已改为 1,000,000），历史预算升到 DeepTutor 的上限 131,072 token；备份 `model_catalog.json.bak-20261029`。
4. **路径改名** 去掉「（从易到难）」（现在是按分值+依赖排序）；`setup_course_space.py` 改为按现名找阅读集并更新课程说明，避免重跑时再造一个重复阅读集（我重跑时误造过一个，已删）。

**10-29 夜补：** 上下文窗口改按真实值 1,000,000；桥的推理强度默认改为 medium（`BRIDGE_CLAUDE_EFFORT`，空串=Claude 默认）。

## 十六、手机端「看不到大纲」的根因与修复（10-29 夜）
现象：折叠屏内屏上 App 里打开会话页，看不到左侧大纲。根因有两层：
1. WebView 对「页面加载后才改的 viewport meta」不可靠（只有同时发生尺寸变化才套用）。App 一开始就是手机布局，所以 `xl:block` 的大纲栏不显示。
2. 即使首屏对了，Next 的前端路由切换（pushState）会重写 `<head>`，把我们塞的 viewport 弄丢，回到手机布局。
修法：①App 把窗口宽度（dp）写进 User-Agent（`DTW/704`）；手机入口（3790）在 600–1024dp 时，于 `</head>` 前**多放一个** `width=1024` 的 viewport（不改服务端渲染的那个，否则 React 水合报 #418）。②前端路由切换后自动整页重新加载（`Android.reloadPage()`，用 loadUrl 而不是 reload，才会按屏幕宽度重算缩放）；折叠/展开/旋转时也整页重新加载。③会话页 1000–1279px 强制显示大纲栏的 CSS 保留。已在模拟器按 Fold8 内屏（1848×2448、420dpi）冷启动→进个性化学习→打开会话，全程桌面布局，大纲栏（5/49，可折叠）和公式都正常。App v1.6。调试用的 `Android.log`（logcat 标签 dtjs）保留。
