导师状态机（TON-1089，DESIGN §5.1 / §5.8）由 `server/core/TutorMachine` 决定流程，`ChatService` 只执行其讲解/追问计划。状态、设置、概念讲解和题卡都是账本事件；对话正文留在 messages，答案键只在服务端。

状态从 IDLE 经 PREPARE、TEACH 到 AWAIT，下一步经 POSE 到 AWAIT_ANSWER；整组提交经过 GRADE、REVIEW、FEEDBACK。选择题为 EXACT，REVIEW 不调用模型，也不伪造异厂复核事件。掌握窗口继续由 `shared/domain/project` 计算。

默认先讲后考；已讲过的 KP 的“继续”直接返回题卡。`tutorIntent` 只匹配整条短句，不把包含“继续”的普通问题猜成命令。直接“考我”可以绕过讲解，三张卡都标 `untaught=true`。当前题组未提交时，继续只重发原题卡，字母不变。

`Projection.taughtConceptIds` 为课程内已完整讲解的概念并集；下一次只完整讲新增概念，重叠概念放入 `recapConceptIds`。显式换模式以及答错两次后的换例子均是回顾，不再写重复的完整讲解概念。只在收到完整、非空、成功的模型回复后记 `explanation_delivered`；取消、截断、掉线恢复和过期回合不能补写。进程启动清理未结束回合后，未讲完的 KP 可以重试。

待答追问先记 `followup_asked.pendingAttemptId`。没有指定题目时，回复涉及当前整组，整组都成为 assisted；仍由投影判断资格。模型上下文只有选项文本，没有 letterMap 或答案键。原文先检测正确选项文本（NFKC、大小写、空白/标点归一），写 LEAK 后才删字母句；LEAK 不因删句而撤销。待追问结束才接受整组提交，避免泄题事件晚于提交。历史反馈中的字母句也不喂回模型。

输出按安全前缀流出；疑似字母引用的前缀暂缓发送。如果后文令整句成为违规句，使用已有 `msg.snapshot` 替换已发出的安全文字，字母本身不会发出。`messages.status` 保留真实终态，`ai_calls.status` 额外记 `letter_ref_stripped`。期中说明段仅首次完整讲解允许，标题“期中怎么考”及正文合计至多三行。

题组设置只接受 1、3。提交前验证完整题组、合法字母及当前回合，再在同一数据库事务内独立提交/判分；重复同答案返回原判分，换答案拒绝。卡片和反馈共用 frozen letterMap。题池不足时排一个 `gen_mcq` job 并显示排队状态；补题 worker 由后续题池卡实现，不虚构题卡或完成时间。

WebSocket 命令（沿用 `/v1/ws` 设备鉴权）：

| `t` | 参数（均带 `courseId`、`threadId`） | 结果 |
|---|---|---|
| `tutor.open` | `kpId`, `conceptIds`, `title` | 绑定每 KP 唯一线程，返回 `tutor.state` |
| `tutor.next` / `tutor.test` | 可选 `pack` | 讲解流或 `tutor.cards`；test 标记未讲题 |
| `msg.ask` | `text`, 可选 `pack` | 短命令先分流；其余为追问流 |
| `tutor.settings` | 可选 `groupSize`, `mode` | 更新设置，返回状态与 `nextLabel` |
| `tutor.explain` | `mode`, 可选 `pack` | 按指定模式讲解/回顾 |
| `tutor.answer` | `groupId`, `submissionId`, `answers:{attemptId:letter}` | `tutor.feedback`，含每题 EXACT 判分 |

模式值为 CURRENT、INTUITIVE、EXAMPLE、FORMAL、EXAM，对应当前讲、直觉讲、换例子、正式讲、考试写法。命令非法时返回 `msg.error`，不结束 WebSocket。卡片重发和状态重建使用账本，讲解续传仍使用 `msg.resume`。

验收命令（先看 `date`、`uptime`，遵守期中暂停窗口）：

```sh
tools/dev/heavy ./gradlew :shared:domain:jvmTest :server:store:test :server:core:test :server:app:test --max-workers=2
tools/dev/heavy ./gradlew :server:core:replay --max-workers=2
```

第二条显式读取本机 `~/Coach/corpus/replay`，可用 `COACH_REPLAY_DIR` 覆盖。仅输出聚合数字，不打包或打印语料。它用真实短句和题卡选项重放五项结构断言；原系统没有 Coach 的 conceptId/状态，概念身份与状态初始化是合成夹具，不能将此报告为历史语义或 live CLI 验证。真实语料未准备好时该命令失败，不静默跳过。

真实 CLI 的状态机走查单独运行 `:server:app:tutorLive`，预计两次导师调用（讲解、待答追问），出题和 EXACT 判分不调用模型。它读取本机 `gradegen/runs/kp` 的已复核题卡以及对应教材备课包，在独立目录创建 Ktor、WS 客户端和账本，走“继续→讲解→出题→三题→追问→整组作答”。缺课程或复核题池会明确失败，不放合成题充数。仅在 8791 没有服务时启动自己的桥，结束时只回收自己启动的桥。

```sh
# COACH_BRIDGE_PYTHON 可指定安装了 tools/bridge/requirements.txt 的 Python。
# COACH_GRADEGEN_DIR 可指定私有 gradegen 目录；COACH_TUTOR_LIVE_OUT 指定独立证据目录。
tools/dev/heavy ./gradlew :server:app:tutorLive --max-workers=2
```

今晚的临时构建规则以 issue 的 2026-10-30 23:55 指令为准：到 2026-10-31 08:00 GMT，可直接执行 Gradle、`--max-workers=6`；先检查全机 GradleWrapperMain 数量，已有两个构建就等待。临时窗口之后恢复上面的 heavy 命令。

集成时先 `tutor.open` 绑定当前 KP，再 `msg.ask` 发“继续”以开讲；讲完后发“出题”。`tutor.cards.cards[]` 就是应绘制的账本题卡（按 letterMap 排选项，保留 groupId/attemptId）。整组提交 `tutor.answer`，消费 `tutor.feedback.grades[]`。先复用现有内容装载和 VERIFIED 题池；题池为空会返回 pool_queued，不会生成假题。通用课程列表和题池生成由对应模块提供。

2026-10-31 00:01 GMT 的真实走查：课程备课包与三道已复核选择题来自本机 gradegen 材料，未使用桩。讲解首字 4229 ms、完整结束；“出题”到三题卡 27 ms，新增 AI 调用 0；待答追问首字 1544 ms、完整结束；三题 EXACT 得分均为 1，掌握窗口 3，passed=true，完整讲解事件 1 条，LEAK 0。两次真实物理调用，桥日计数从 0 到 2，余额 58/60。测试自己启动的桥正常回收。只有这一个 KP 做了真实走查；跨 KP 概念语义仍未作全量真实验证。此前一次走查因测试客户端的连接等待阻塞调度而退出，发生在任何模型调用前，修正独立调度后复跑通过。

不限额桥的 health 会返回 `daily.remaining=null`；tutorLive 将明确的 JSON null 视为不限额，仍检查 `rate_limit.all_paused`。有限余额不足、暂停、余额字段缺失或格式错误均拒绝。复跑可直接使用已运行的 8791，测试不会停止借用的桥。端口空闲时才启动自有桥，默认与目前“不限调用次数”的政策一致，也允许显式环境变量配置；没有 Python 环境时给出设置 `COACH_BRIDGE_PYTHON` 或安装 `tools/bridge/requirements.txt` 的提示。
