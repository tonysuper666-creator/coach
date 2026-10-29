# 立项卡：整合设计 → 裁决分歧 → 拆卡开工（负责人 Mika）

李政 2026-10-29 晚决定：**不再给 DeepTutor 打补丁，由 Multica 团队从 0 把 StudyCoach 与 DeepTutor 有机结合，做桌面（macOS，之后 Windows）+ 安卓原生应用**。他要求先设计好；设计已由三个模型各自独立完成并交叉评审，现交给你整合、裁决、开工。

## 四行（ambitious-tasking）
- **目标背后的目标**：他每天只用 Coach 学习，不再回头开 DeepTutor——一个入口、每个对话挂在学习对象上（用户不用手选笔记本/题库）、掌握度按“已通过 / 稳固”两层并能下钻到原题原答、“学了多少”按期中分值回答。
- **可度量目标**：①本卡 24 小时内交付 `docs/DESIGN.md`（最终设计）+ ADR + M0 卡片拆解；②M1（ECNM10119 单课程、安卓 + Mac 闭环）完成后他连续 7 天只用 Coach 学习；③导师回答首字 p50 ≤5 秒（按请求类型验收，见 d3）；④打开 App 到开始学习 ≤3 次点按。
- **原始材料与工具**：见下“材料”。真实订阅 CLI（Claude/Codex/Grok/Antigravity）、本机 SDK/模拟器、DeepTutor 的真实库（只读）。
- **外部验证**：设计里每个决定都能追溯到某份设计或某条核查证据；spike 与验收用真实 CLI 和真实课程页，不用 mock；验收者自己复跑。

## 已拍板（别再请示）
KMP + Compose Multiplatform（安卓原生 + macOS，之后 Windows）；服务端放这台 Mac、设计成可迁移；AI 经订阅 CLI（沿用现有订阅桥的**副本**，端口 8791，DeepTutor 的 8790 一个字不动）；一本事件账本；每个对话挂学习对象；立刻全面开工（但见“约束”里的 CPU 一条）。

## 材料（都在本仓库 `~/dev/coach`，agent 可直接读本机路径）
- `docs/background/新应用立项方案v2.md`：产品定义、原则、里程碑草案。
- `docs/background/StudyCoach与DeepTutor深度对比与结合方案.md`：两边的对象模型、闭环、割裂点。
- `docs/design/round1-{opus,sonnet,astra}-full.md`：三份完整设计（每人负责全部）。
- `docs/design/round2-critic-*.md`：三份交叉评审。
- `docs/design/committee-result.json`：评审秘书抽出的 **20 条共识** 与 **8 条需外部事实裁决的分歧**（d1–d8）及核查员的裁决/证据。
- StudyCoach 源码（只读，已归档）：`gh repo clone tonysuper666-creator/studycoach`；DeepTutor：`~/DeepTutor`（订阅桥 `tools/subscription_bridge.py`，Python 包在 `.venv/lib/python3.12/site-packages/deeptutor`）。
- 用户的真实数据：DeepTutor 库 49 个知识点、108 题、笔记本；期中分值覆盖表 `docs/background/期中分值覆盖表.md`。

## 你要做的（按顺序）
1. **整合与裁决**：以 20 条共识为底；逐条对 d1–d8 采纳或推翻核查员的建议（理由写进 ADR）；吸收评审里三份都漏掉的点（见下）。可点名 Architect 做评审与第二意见。**路线分叉/品味选型**走拍板白名单（在 issue 上写 metadata `ask`，大白话写 `brief`）。
2. **产出**：`docs/DESIGN.md`（团队能据此开工：决定、接口、数据形状、验证办法、不做清单）、`docs/adr/ADR-0001…`（栈、桥副本、证据与掌握、传输与鉴权、渲染路线、迁移）。
3. **拆 M0 卡并按链派活**（`ambitious-tasking` 四行；`depends_on/unblocks` 写 metadata；同一 agent 同时进行的执行卡 ≤2；`engineering`/`android`/`frontend`/`reviewer`/`cross_review` 链按规则 §2）。M0 至少包括：S0 工具链（含 Gradle 项目与版本钉死）、公式与流式渲染 spike（原生 SVG + 自绘 Painter vs 退路）、桥副本真流式与终态标注、出题与批改链路的真实端到端（含植入错键的故障注入）、账本 + 离线发件箱 + 回档、迁移工具的只读对账。每张卡写清判定标准与退路。
4. **验收**：普通事项 `reviewer` 链；关键事项（鉴权、证据与掌握写入、迁移、跨端行为）加 `cross_review`；“交付不自评”，首行写「结果：达到/部分达到/未达到 + 数字证据」。
5. 每天收口复盘照现有规则；进展写在对应 issue 评论里，不单独发知会。

## 约束与注意
- **不要动 DeepTutor 的运行文件**（8790 桥、数据库、补丁）。期中 2026-11-02 14:25 前它必须稳定：他在用它复习。
- **CPU**：他选择了“立刻全面开工”，但这台 Mac 同时跑他的 DeepTutor。大构建与模拟器请 `nice -n 15`，Gradle 加 `--max-workers=2`，同一时间最多一个重构建；他考试当天（11-02）上午起暂停 spike 以外的重负载。
- **额度**：不新增花钱；Expert（Astra）与 Sage 只点名；Claude 订阅也供他本人用，工程主力放 Codex/CB，Claude 只管判断与验收。
- **GitHub 仓库尚未创建**：自动模式拦下了“新建私有远程仓库并推送”（属于对外上传），已转给李政确认。在他确认前，所有工作先在本地仓库 `~/dev/coach` 的分支上做，PR 流程相关的 done 判据暂不适用；确认后我会在本卡评论里通知你。
- 他的理念：限制只为保护用户，代码量/行数/门禁数只报告不设卡；测试只测用户感知到的质量；**不要重蹈 StudyCoach 治理过重的覆辙**；不留“确定性占位冒充完成”。
- 用户设备：三星 Z Fold8（外屏 1248×1972；内屏 1848×2448）、OPPO Pad Mini（2520×1680）、Mac。折叠/展开/横竖屏四形态都要好用。
- 订阅 CLI 的风险：Antigravity 个订条款有被停用风险，Antigravity agent 并发压到 1–2，不无人值守高频轮询。

## 评审里三份设计都漏掉的（请吸收）
`$` 既是货币符号又是公式定界符（课程库 332 行、11 道题干会被误判）；手机离线时选择题的即时反馈；三个常驻进程的托管与自愈；切换与回退计划（DeepTutor → Coach）；开发期额度可见性；逐字证据阈值对中文的误拒（d6 实测 72%）；服务端回档后的游标纪元；订阅掉登录时的用户路径；分发与签名；手机输入公式。

## 核查员对 d1–d8 的建议（供你裁决，不是定论）
- d1 公式：原生（服务端 MathJax 出 SVG）+ **自绘 Painter**，不用 coil-svg 做桌面主路线（它不画 `<text>`，中文会静默丢）；KCEF 已归档，证据最弱。
- d2 订阅桥：Astra 列的四条缺陷全部成立；采用 Opus 的“副本 8791”+ Astra 补丁清单 + 新发现（路径迁出、终态标注、关闭隐式兜底、物理调用数、并发协调）。
- d3 首字：按请求类型验收，不按上下文大小；追问/问答 p50≤5s、p90≤8s（n≥20，排除“仍在思考”之类进度帧）。
- d4 已通过 = **最近 3 次有效首答全对**（题目指纹互不相同、答案键已复核），通过后锁存；题池按实测参数（1 次 Claude 出 3 道 + 异厂盲复核）常备，同步出题 20–40 秒与“零等待”冲突。
- d5 工具链：桌面端锁 Gradle 9.5.0 / Kotlin 2.4.0 / CMP 1.12.1 并显式钉 3 个传递依赖，可 `--offline`；安卓一半没缓存，要求 compileSdk 37 而本机只有 34/35；Compose 桌面在 macOS 27 beta 上的可用性需 S0 实测。
- d6 逐字证据：不照搬 SC 阈值；改词元边界（汉字单字、整个数字各算一个词元），门槛放宽为“≥8 有效字符”之类的折中，并统一各种减号。
- d7 传输：默认 Tailscale IP 明文 + Android 网络安全配置只放行该 IP（不要照搬 DeepTutor 壳的全局 cleartext）；`tailscale serve` HTTPS 需要他改 tailnet 设置，留作可选。
- d8 SQLite：服务端 sqlite-jdbc 锁稳定版本；**Android 系统 SQLite 没有 FTS5**，客户端离线搜索需打包自带 SQLite；`?immutable=1` 读运行中的 DeepTutor 库是否安全需再核。
