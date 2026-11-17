# 朋友入口：候选切换与回滚审查单

TON-1688 的生产方案交付。本文是供 reviewer 审查的执行设计，**不是生产授权，也不是已冻结的部署脚本**。协议探针、秘密传递和资料边界沿用 [friend-public.md](friend-public.md)。回滚隔离采用下述朋友凭据哈希域；不部署启动策略或 launcher，不以历史入口退役作为上线前置。

## 历史快照与待冻结候选

2026-11-17 首版方案对实际安装记录、LaunchAgent 和 Funnel 做了只读核对；没有向服务发送请求，也没有证明当前空闲或健康。下表是当时快照，**不是切换候选或当前安装证明**。

| 项目 | 本次观察 |
| --- | --- |
| 快照安装源码 | `eb2d87a7ad065a0e411e0d00ee353e979d4b33a3` |
| 原 plist SHA-256 | `e4eba57eab5cd8fc8a8b605d3001eaeb420ea92722d4178a3bda34013091be5b` |
| 原 installation.json SHA-256 | `bbd39967cc445c12e67dd9388741dfc3a93d31101c3987cc8b017b32cd8fc8f3` |
| 原正常监听 | `18791`；plist 未配置 `COACH_PUBLIC_PORT`、`COACH_API_STATE_DIR`、`COACH_FRIEND_CONFIG` |
| Funnel | `funnel status --json` 退出码 0，返回空配置；不是后台权限或互联网连通性的证明 |
| 入口代码快照 | `e73dddec8ea500d7409e5cdb7b67f596e97376a0`，包含 #371、TON-1686/1687，不含 #372/#373；不得用作已冻结候选 |
| 首版方案起点 | `6ee57904d61fdffa96f1039925d4e214f4427ccc`；相对上述代码快照只变更 `docs/design/reflow-options.md` |

不把源码 SHA 当作已验证的安装目录。最终切换必须绑定确切 source head、构建产物清单及其哈希、原/新 plist 和安装记录哈希、回滚 release、数据目录身份和执行器哈希；证据文件留在受限私有目录，不上传公开仓库或 Actions artifact。

TON-1663 已于 2026-11-17 13:01Z 明确释放窗口和执行器租约，不再以其 issue 状态 done 为前置。释放后只读重查的实际安装源码为 `4c99ac32caa1a7f0fae715ae4153c66ad10bd82e`；其旧授权 SQL 仍不区分朋友，不能按首版 `eb2d87a7` 基线部署。旧 stage、审批文件及实际持久控制器本轮未改。任何 head、plist、安装记录、产物、执行器或端口变化，都要求重新绑定并复核。

PR #373 已合并为 `3312d2c954fe3fa5e49eb21f8ada1b0a906984d1`，包含 `friend-windows.yml` 的 package/probe 和原生驱动；本文修订从该 main 起点提交正式 PR。最终候选必须包含 #371/#373，并在租约释放后从最新 main 重新选择、绑定产物。已合代码不等于 Actions 或外网已实跑，自写材料截图不作为真实教材验收。

## 配置变更与数据边界

私有 staging 中逐项比较配置，允许的变化只有已审查的 release 路径替换，以及新增 `COACH_PUBLIC_PORT`、`COACH_FRIEND_CONFIG`。保留 owner 正常端口、原数据目录、配对凭据、资料目录和服务管理设置；**本次不迁移 API state 根目录**。

| 变量 | 要求 |
| --- | --- |
| `COACH_PUBLIC_PORT` | 选一个当时未占用的有效端口 P；排除 18791、正常端口、bridge、mathsvc 和任何现有监听；切换前再次检查，冲突就停止，不关占用者 |
| `COACH_API_STATE_DIR` | 保持原设置及解析后的实际目录不变；原来未设置就继续未设置，并固定原服务 HOME，使用既有 `Coach/state`。不得指向新空目录；JVM 与 bridge 的解析结果须相同 |
| `COACH_FRIEND_CONFIG` | 受限的绝对私有配置路径；JVM 与 bridge 一致，显式额度及全局 `paused: true` 起步，不依赖缺省额度 |

`Main.kt` 的朋友端口仅绑定回环，且拒绝 18791 和正常监听端口；朋友端口绑定失败会使启动失败，须走回滚。`ChildServices.kt` 的子进程继承环境，因此不能只给 JVM 或只给 bridge 设置上述路径。已有 API 凭据继续由 bridge 的 Keychain 路径读取，不放入 plist、代码、评论或 Actions。

`tools/bridge/api_budget.py` 在同一 `api-credit-ledger.json` 保存 owner 的 `cycles`、`months` 和朋友的 `friends`；朋友预留也占 owner 月预算。朋友隔离依靠 `friends[id]` 与 `state/friends/<id>/`，不另起预算根。在受控停止、持锁且无在途写入的检查点，私下记录实际 state 目录身份，以及 `cycles` 的 spent/limit/reserved、`months` 和朋友明细；启动后首个请求前核对连续性，随后按实际调用核对费用与预留增量。保留 `api-spend-*.json` 汇总和未知预留，切换与回滚均沿用同一账本，不重置、不回拷旧文件。目录身份或连续性不符即停止。未来若确需迁移，须另审受控完整复制和一致性证明，不属于本方案。

配置中的朋友名、设备 ID、实际地址及配对回包只存私有记录。先完成原 owner 的五分钟验收，再签朋友配对码，避免改写单例配对码影响原有验收。解除暂停也必须有明确测试额度；暂停不能撤销 token、停止在途请求或代替关闭入口。

## 回滚隔离：朋友凭据使用独立哈希域

2026-11-17 Architect 裁决替代此前的“盘点全部入口并 arm guard”方案。`LedgerStore` 对 owner 继续使用 `sha256(secret)`，朋友配对码和 token 只使用 `sha256("coach-friend-v1:" + secret)`。`friend_name IS NOT NULL`（包括空字符串）即朋友，鉴权和兑换均不回退到 owner 算法。旧版 `4c99ac32` 只计算普通哈希，因而不能接受新朋友 token 或兑换新朋友码，无论从控制器、历史 stage 还是直接 JVM 启动。仅有当前代码的 SQL 单测不能证明实际旧二进制兼容，仍须运行下述隔离演练。

两表新增 `friend_hash_version INTEGER NOT NULL DEFAULT 0`，owner 为 0，新朋友为 1。摘要本身无法证明所用算法；版本标记使迁移可判定。每次候选初始化在同一事务中撤销标记不为 1 的活跃朋友设备，并删除这类待用朋友码，保留 owner 凭据和已有撤销时间，不兼容早期测试库的普通哈希朋友。新朋友重启后仍有效。旧版写 owner 码不会清除旧 `friend_name` 或版本；候选仍只做朋友域比较，拒绝该普通哈希码，须由候选重新签 owner 码，不能把它兑换成朋友。

回滚先尝试关闭本次准确 Funnel 规则（不用 `reset`），受控停止候选及子进程，再恢复经审查的旧代码、plist 和安装记录；保留同一数据库、原 HOME/API state 和切换后的全部资料、费用、预留、撤销及结算。禁止回拷旧数据库或旧预算。旧版不认识 `COACH_PUBLIC_PORT`，其朋友后端不监听；关闭 Funnel 不再是凭据隔离的安全前提。关规则失败需记录并确认朋友端口已停止，不能因此把 owner 服务锁停。候选尚未建表就失败时，也可恢复旧版。

[离线吊销工具](friend-revoke-offline.md)保留为显式维护和测试清理手段，不再挂入自动启动。若执行它，仍须先停机并持锁覆盖完整调用；非零退出（包括提交后读回失败）保持停机并人工核查，不能重试后冒充一次干净成功。常规降级拒绝新朋友凭据不依赖运行它。早期普通哈希朋友须由本次迁移撤销；未完成迁移的测试库不能当作新格式验收。

## 执行器适配与隔离演练

**不创建或部署 `friend-start-policy.json`，不 arm `friend_service_guard`，不部署 launcher。** `ton1175_switch.py` 的新方案使用仅含 `publicPort`、`config` 的 `friendAccess`；无需策略、持久 helper 或吊销。stage 记录 `friendCredentialDomain=coach-friend-v1`、初始暂停配置及控制器文件哈希，switch 前复核配置权限、内容、端口空闲及控制器。这个标记不证明二进制能力，冻结候选审查和实际旧/新 JVM 演练仍须证明包含朋友哈希域实现。仅新增两个朋友变量，保留原 HOME、API state、其他配置、900 秒活动保护、指针事务及五分钟验收失败回滚。恢复仍核对控制器，不依赖朋友配置存在、仍暂停或朋友端口空闲；不调用 Funnel 或离线吊销。旧三字段（含 `policy`）模式仅保留历史 stage 的原约束和恢复兼容，不用于新生产方案，不把旧 stage 静默转成新模式。TON-1663 的原 stage 固定旧候选，不复用其审批。历史入口归档/拒绝桩另列 backlog 卫生工作，不挡 TON-1688，也不宣称同用户权限隔离。

生产前仍需 reviewer 和独立 cross_review 对实际冻结候选、执行器和恢复方案复核；两者与执行者异厂，且彼此不是同一人或同一模型。代码普通审查不等于生产审批。

在隔离 HOME、独立端口、独立临时配对和账本下，运行实际候选产物及实际回滚版本，至少取得以下证据。不得连接生产资料或占用 Windows 桌面。

| 演练 | 必须观察到的结果 |
| --- | --- |
| 入口隔离 | `publicOnly=true`；20 条私有路径和错误方法实际发到候选监听，空 404；匿名 401；不能只复用探针的模拟响应单元测试 |
| 配置与端口 | JVM/bridge 同一既有 state 和朋友 config；切换及回滚不改变 state 根；端口冲突导致拒绝切换；正常监听不对公网开放 |
| 朋友与账本 | 用隔离账本预置非零 owner spent、limit、reserved 及月汇总，再切换/回滚确认不清零；朋友费用同时计入 owner 月预算，A/B 日明细互相隔离；A 撤销 B 可用，限额第二次拒绝，实际费用与未结预留可对账 |
| 旧版回滚 | 在新版本建 owner 与朋友、写入数据后回退；不运行 guard 或离线吊销，旧版保留 owner 配对和新写入，拒绝仍未撤销的新格式朋友 token 和待用码；回到候选后朋友仍有效，旧版签码的残留朋友名不能升级为朋友权限 |
| 故障路径 | 候选迁移前失败可恢复旧版；迁移事务失败拒绝候选；数据库身份不符或指针恢复失败需人工恢复，不清库、不重签 owner；不因 Funnel CLI 失败持久闭锁 owner |
| 数据兼容 | 旧版读取实际迁移后 schema，并保持事件前缀和新增写入；没有偷偷恢复旧数据库或重签 owner 凭据 |

隔离 JVM 命令：`python3 tools/smoke/friend-rollback.py --config "$COACH_ROLLBACK_CONFIG"`。配置是当前用户所有、0600 的规范绝对路径，字段为 `oldApp`、`candidateApp`（实际 `bin/app`）、完整 `oldHead`、`candidateHead`、`tailscale`。不要开启 shell trace；源码与产物对应关系另留私有冻结记录。脚本只接受空 Funnel，不新增规则；临时 HOME、端口、空资料目录与预算 fixture 均独立，关闭受管子服务、不发付费请求。成功仅输出固定标签和整数计数，失败退出 1。此脚本不执行 LaunchAgent 指针事务或 bridge 费用结算。

SQL 行为小实验只能佐证风险及事务设计；离线供应商 SSE 或合成 fixture 只能证明协议，均不能作为真实教材/API、实际执行器回滚或原生验收结论。

## 有序执行与失败处理

以下生产步骤尚未执行。窗口租约已释放，仍按顺序补齐非生产、候选冻结及交叉审批门禁。实际命令和受限路径由冻结后的 stage 记录给审查者，命令行不得含地址、token 或配对码。

1. **冻结**：确认窗口与文件租约释放、最终 main 冒烟完成，重新采集安装指针、plist、Funnel 配置及数据身份；从最新 main 选择包含 #371/#373 的确切候选，制作并验证不可变安装产物及依赖。使用 `ton1175_verify.py` 的产物证明流程，不能只读取 `source-head.txt`。原生窗口释放后才派发 `friend-windows.yml` 的 package 阶段；记录 run ID、head SHA 和包哈希，probe 必须引用该成功 package run。工作流核对 run 的 head/event/path/conclusion，再核对包内 `commit.txt == GITHUB_SHA`；main 已移动就重新构建并审查，不能混用旧包。
2. **非生产门禁**：上述矩阵和实际候选自查通过，备份及兼容恢复演练通过。首次公网暴露也须先审批：独立 HOME、无生产数据/凭据、有限额度的候选监听先过本地门禁，经审查才临时以唯一 Funnel 规则指向该朋友回环端口，运行下述协议和原生 package/probe 检查；结束关精确规则、吊销全部测试身份并核对状态。不得占用他人的 serve/Funnel 配置，不复用生产朋友码。临时入口不指向 18791，失败保持关闭，不进入生产切换。临时外网权限或窗口未就绪就保留此门禁未通过，不为赶进度先暴露生产。取得外网结果后，生产 reviewer、独立 cross_review 对冻结候选、执行器和回滚计划明确批准；准备原配对设备和获准真实资料，有缺项就不申请生产窗口。
3. **真实空闲**：以 `tools/dev/activity-coverage.md` 要求取得连续 900 秒完整活动证据，绑定同一进程、启动时间和安装身份；存在活动 WebSocket、用户请求、观察缺口或证据过期即重取。健康检查、进程空闲和“日志没看到请求”都不替代此门禁；证明封口后直到受控停止都保持保护。
4. **受控切换**：在保护下做一致性备份和写入水位核对；停止准确目标实例，原子更新已批准的 plist/安装记录，启动候选。Funnel 此时仍关闭，配置仍暂停朋友计费。从受控启动开始计五分钟，在已准备好的原生设备检查原 owner 配对、真实讲课和做题、账本及身份。失败或超时立即按前述步骤回滚；不能把重试或重签身份当成功。
5. **开放**：owner 验收通过后复核没有其他 serve/Funnel 配置，再按 [Funnel 命令及撤销](friend-public.md#funnel-命令及撤销) 开唯一 HTTPS 443 → `127.0.0.1:P`。核对唯一目标为朋友端口，**Funnel 永不指向 18791**。配置漂移、非预期监听或任何私有路径泄漏，立刻关本次入口并暂停后续测试；如需回退旧代码，按前述格式兼容及指针恢复步骤执行。
6. **生产外网复验与收尾**：入口开放后，对同一冻结候选重新运行下面两类手动工作流，不能用临时实例的成功代替生产结果。真实朋友资料/API、额度与撤销在获准私有原生环境另验；窗口释放和最终 main 冒烟通过后才派发 package/probe，不改 tonii。本机管理侧无论成功失败都吊销全部测试朋友，确认 401、清理临时秘密，保留费用与撤销记录。

两类外网工作流各自留 head、run 链接、实际命令与退出码；仅在对应窗口及门禁获准后派发：

| 工作流 | 秘密、资料及证据边界 |
| --- | --- |
| `friend-public.yml`：surface/revocation | 受保护 environment 的步骤 env 注入 `COACH_FRIEND_*` 及 A/B 临时凭据，配对回包另遮蔽字段；禁 shell trace，无 artifact；只证明协议与撤销，不发计费讲解 |
| `friend-windows.yml`：package → probe（`package_run`） | probe 同样限定 main、受保护 environment；Mac 先预配 B，确认 B 可用后再签 A；步骤 env 注入 `COACH_EXTERNAL_URL`、`COACH_EXTERNAL_CODE_A`、`COACH_EXTERNAL_B`（B 的短期凭据 JSON），驱动 add-mask，不写入 PowerShell 源码或参数。B 不计入 Windows 原生配对验收。只用驱动自写材料；配对块消失后才截图。artifact 仅限现有自写材料证据白名单、包和提交记录，不能加入真实教材、地址、身份、凭据或原始日志。Mac 操作者须提前就位，在 Revoke 最多 180 秒内撤销 A 并验证 B 可用；超时是失败，不能当已撤销 |

package/probe 证明原生安装、配对、讲解和截图流程，不证明朋友自己的真实教材/API 或真实额度已验收；后者只在私有环境保留原始材料和证据。生产及临时实例分别登记测试身份，清理覆盖两套工作流的临时 secrets，不能只依赖 runner 清除本机身份。

公开交付只留确切 head、命令与退出码、固定检查标签、脱敏计数及运行链接。实际地址、tailnet、身份、材料、原始配置、Keychain 凭据、SQLite 和活动日志留私有环境。

## 本次证据状态

| 项目 | 状态 |
| --- | --- |
| #371 协议探针与 9 项安全测试 | 已由异厂 reviewer 验收并合并；不是本次重新实跑或生产验收 |
| #373 原生驱动及 package/probe 代码 | 已由异厂 reviewer 验收并合并；Actions、实际包安装及外网未验证 |
| 实际安装及空 Funnel | 租约释放后重查：实际源码 `4c99ac32`，Funnel 空；私有配置哈希另存，不证明当前空闲 |
| 执行器适配 | 新增无策略的朋友配置适配；旧 guard 未部署。fixture 事务及故障恢复另验，冻结候选完整指针事务、实际生产控制器 CLI 和原生五分钟验收仍待验证 |
| 历史 guard 方案的实际旧/新 JVM 隔离回滚、错误方法真实监听 | `4c99ac32` 实际安装与 `e244d664` 构建产物演练通过：20 条私有路径、4 组错误方法；owner、新事件、非零预算文件及产物保留，朋友 token/待用码拒绝；未包含 LaunchAgent、bridge、真实费用或原生客户端 |
| 哈希域代码与隔离 JVM | `093964d6` 源码：store/app 共 383 项测试（含新增 7 项）通过，无跳过；实际旧安装 `4c99ac32` 与候选 JVM 不经 guard/吊销降级，未撤销朋友 token/待用码均拒绝，owner/新事件/非零预算 fixture/产物保留；回到候选朋友仍有效，残留朋友名的旧 owner 码拒绝。20 条私有路径、4 组错误方法通过；不是指针事务、bridge/真实费用或原生验收 |
| 哈希域候选的生产 reviewer + cross_review | 未取得 |
| 15 分钟真实空闲、五分钟原 owner 原生验收 | 未执行 |
| Actions 实跑、外网真实教材/API、真实额度与清理 | 未验证 |

整卡继续保留；本段可独立审查和合并，不能据此把生产门禁或整卡标为完成。
