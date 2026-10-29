# Coach 设计任务书（三模型共用）

你们是一支设计委员会的三位成员，要为新应用「Coach」（代号）做**从 0 开始的设计**。本轮只产出设计，不写生产代码，不装软件，不改 ~/DeepTutor 的运行文件，不读 ~/DeepTutor/sealed、任何 .env/凭证。除你自己的输出文件外，一律只读。

## 背景（必读，按顺序，够用即可，别通读无关文件）
1. ~/DeepTutor/新应用立项方案v2.md —— 产品定义、原则、推荐架构、里程碑（用户已拍板：Kotlin Multiplatform + Compose Multiplatform；先 Mac 后 Windows；安卓原生；服务端先放这台 Mac 但可迁移；订阅 CLI 接 AI，无 API Key；立刻开工）。
2. ~/DeepTutor/StudyCoach与DeepTutor深度对比与结合方案.md —— 两个现有应用的对比、割裂点诊断。
3. ~/DeepTutor/DeepTutor改造记录.md —— 我们现有的订阅桥、真流式、补丁、手机入口、安卓壳、折叠屏适配的全部做法与数据。
4. ~/DeepTutor/原生应用评估.md。
5. StudyCoach 只读源码：/private/tmp/claude-501/-Users-zheng-li/af30aae9-a910-489f-aa59-a62aa8ea8550/scratchpad/sc/src （重点：packages/contracts、domain、projections、ai-runtime、design-tokens、docs/architecture、apps/web/src/learn）。
6. DeepTutor 源码（只读）：/Users/zheng_li/DeepTutor/.venv/lib/python3.12/site-packages/deeptutor （重点：learning/*、capabilities/mastery/*、api/routers/unified_ws.py、mastery_path.py）。
7. 订阅桥：~/DeepTutor/tools/subscription_bridge.py。

## 已定的决定（不要再争论，除非发现致命问题，致命问题要明说）
- 栈：KMP + Compose Multiplatform（安卓原生 + macOS + 之后 Windows）；服务端 Ktor（JVM），领域逻辑是共享的 KMP 模块。
- 一本事件账本，所有状态（位置、掌握度、复习队列、日记）是它的纯函数投影。
- 每个对话都挂在一个学习对象上（知识点 / 复习项 / 课程），用户永远不手动选笔记本、阅读集、题库。
- AI 经订阅 CLI（Claude、Codex、Grok、Antigravity）接入，已有桥（OpenAI 兼容、真流式、兜底链）可先复用。
- 用户的理念：**限制只为保护用户**——代码量、行数、门禁数只报告不设卡；测试只测用户感知到的质量；不要治理过重（StudyCoach 的教训）；不要“确定性占位冒充完成”。
- 用户设备：三星 Z Fold8（外屏 1248×1972 10:16；内屏 1848×2448 4:3）、OPPO Pad Mini（2520×1680 3:2）、Mac。折叠/展开/横竖屏共 4 种形态都要好用。
- 当前课程：ECNM10119 健康经济学（49 个知识点、教材页码标记、108 道题、期中 2026-11-02）。数据要能从 DeepTutor 迁入。
- 公式（LaTeX）渲染、流式 Markdown 是已知最大技术风险。

## 输出要求
- 中文；每个论点给**理由**，并在引用现有代码/文档时给出**文件路径**；不确定就标 [未证实]。
- 不要写成一篇漂亮的论文：写成**团队能直接据此开工的设计**——决策、接口、数据形状、边界情况、风险、验证办法、该先做的 spike。
- 明确区分：「决定」「建议（待团队裁决）」「开放问题」。
- 篇幅：≤ 2200 字（中文字数），可含代码块/表格（不计字数过严，但别灌水）。
