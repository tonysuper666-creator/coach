# 朋友讲解入口：上线和外网 Windows 验收

仅适用于 TON-1688 已获同意的朋友入口。四条路由及额度契约见
`docs/FRIEND_ACCESS.md`。本文不保存实际地址、tailnet 名、配对码、设备令牌或教材。
不要把私有操作记录或原始服务日志上传到公开仓库/Actions。

已合入的协议探针和 environment 配置以 `docs/operations/friend-public.md` 为准。
本文另提供后续原生窗口验收步骤；现行 Windows 排队约束解除并完成最终 main 冒烟前，
不派发 package/probe，不新增 winbox 桌面测试、不做安装包、不安装到 tonii。

## 上线前门禁

生产服务端升级需要一位异厂 reviewer 和另一位异厂 cross_review，先审代码、
候选版本和下面的切换/回滚计划，再实际切换。两位前置卡 done 不等于生产升级通过。

1. 记录当前 `installation.json`、LaunchAgent plist 的 SHA-256、安装 head 和 release；
   私有备份存 `~/Coach/service/` 下的独立目录，目录 0700、文件 0600。保留旧 release。
2. 从已审核的干净 main 构建候选，先在随机回环端口运行：
   `tools/dev/heavy ./gradlew :server:app:installDist --console=plain`，再用桥的 Python
   运行 `tools/bridge/tests/friend_acceptance.py`。该测试的合成材料只验证传输/隔离，
   不代表真实教材讲解质量或生产切换已通过。
3. 在候选的最终不可变 release 目录安装桥与 mathsvc 依赖，检查真实依赖和构建来源。
   从当前生产库作一致的只读 SQLite backup，在隔离目录和端口验证原桌面令牌、
   原学习历史、讲课和做题；生产数据不写入、不打印。
   `check-production-copy-upgrade.py` 固定了 TON-1175 的旧 head，**不能直接用于本次**；
   先由独立审核者确认当前 baseline 和相应演练，不能删除它的校验来凑通过。
4. 在私有候选 plist 中保留现有环境（包括 reader library），仅更新已审核 release
   路径、依赖路径和新增 `COACH_PUBLIC_PORT=18792`。18792 是本机端口示例，先确认空闲；
   不使用 18791、8790、8791。新装服务可显式用
   `python3 tools/dev/coach-service.py install --public-port 18792`；已有生产服务应先暂存
   候选并审核，不在停服窗口执行会联网装依赖的 `install`。
5. 最后一次真实用户请求距切换至少 15 分钟，紧接着复核安装 head/记录摘要没有变化。
   无法证明空闲就不切换。仅停止受管 `local.coach.server`，原子替换审核过的 plist 和
   installation 记录，再启动候选。五分钟内验证原配对、讲课、做题和子进程健康。
   任一失败立即停止候选、恢复原 plist/installation 并启动旧 release。
   **回滚保留当前数据库和切换后写入**；不能用备份覆盖用户的新学习记录。

## 只公开朋友监听

生产验收通过后，先确认新端口只监听 `127.0.0.1`，四条路由生效，原来的
18791 仍只在回环/tailnet。运行前读取 Funnel/Serve 配置；若已有别人的配置就停止，
不要覆盖，也不用 `reset` 清空。

```sh
TAILSCALE=/Applications/Tailscale.app/Contents/MacOS/Tailscale
"$TAILSCALE" funnel status --json
"$TAILSCALE" serve status --json
# 仅在配置为空且本机 18792 已确认是朋友监听时：
"$TAILSCALE" funnel --bg --https=443 http://127.0.0.1:18792
"$TAILSCALE" funnel status --json
```

在本机私下检查结果仅有一条 443 → 回环朋友端口的规则。不要把输出贴到公开位置。
不得将 18791、桥、控制台或整个 Mac 暴露出去，不开系统防休眠设置。

撤销自己新增的映射（不清空其他配置）：

```sh
"$TAILSCALE" funnel --https=443 off
"$TAILSCALE" funnel status --json
```

若生产回滚，先关这个映射；旧服务不需要朋友端口。

## 外网 Windows：先包、后配对码

工作流 `.github/workflows/friend-windows.yml` 只接受手动派发，无 PR/push 自动触发，
只允许 main，不会在构建阶段读取朋友密钥。两个 run 都必须来自审核后的同一 commit。
probe 使用 `friend-public` environment；先按协议探针手册将该 environment 限制为 main。

1. 运行 `phase=package`，等 Windows MSI 和 UI 驱动构建成功，记下 run ID。
   安装包是未签名开发包；只在临时 Actions Windows 运行机安装，不在用户设备发布。
2. 在 Mac 私有朋友配置中建立本轮专用名字 `External acceptance A`、`External acceptance B`
   （不得与已有朋友重名，重跑前先撤销上一轮 A/B 并核对），B 的
   `daily_calls=1`。合并配置时保留原字段；先备份，结束恢复时保留期间其他人的修改。
   两个朋友只用于本轮，账本保留，不删除或清零 quota/reserved。
3. **先在 Mac 兑换 B，再签 A**：`pairing_code` 是单例，新码覆盖旧码，不能同时签 A/B。
   对已批准的临时隔离实例使用 `tools/smoke/friend-prepare-windows.py --output PRIVATE_FILE`：
   通过私有进程 env 传 `COACH_PREPARE_OWNER_URL`（管理回环端口）、
   `COACH_PREPARE_PUBLIC_URL`（朋友回环端口）、`COACH_PREPARE_OWNER`（owner 凭据 JSON）。
   输出父目录须 0700，文件须不存在；工具生成 0600 文件，仅含 `codeA` 和
   `credentialB`（`deviceId`、`token`、`role=friend`）。B 兑换和授权失败时不签 A，
   失败会尝试吊销本轮 A/B，退出 1 后仍须人工核对，不得继续派 probe。
   通过 `gh secret set --env friend-public` 的 stdin 设置 `COACH_EXTERNAL_URL`、
   `COACH_EXTERNAL_CODE_A`、`COACH_EXTERNAL_B`（上述 B 的完整 JSON），绝不把值放进
   命令参数、仓库文件或 workflow inputs。URL 使用 HTTPS 443 的 Funnel 根地址。
   B 是 Mac 预配的短期对照身份，**不计作 B 的 Windows 原生配对验收**；只有 A 在 Windows
   配对 UI 兑换一次性码。B 只经步骤 env 进入驱动，解析前后 add-mask，不进 artifact。
4. 立即手动派发 `phase=probe, package_run=ID`。A 配对码五分钟过期；队列过久或码过期
   就签新 A 码、更新其 secret 并重跑 probe；B 已消费当日额度时停止本日重跑；另日重跑仍须先撤销并重新预配。
   同名 B 的朋友日账本会保留，换设备不能恢复额度；不得清零账本、延长码有效期
   或拿 B 令牌代替 A 的一次性码。
   environment secrets 在 job 开始时读取（[官方说明](https://docs.github.com/en/actions/reference/security/secrets)）；不要试图在运行中换 secret 修复该 run。
5. Probe 在安装和任何计费请求之前，先运行同一 commit 的
   `python tools/smoke/friend-public.py transport`：仅步骤 env 传 URL，不传配对码或令牌。
   必须通过公网 DNS、匿名 HTTPS 401 和 80/8443/10000/18791 主机路由检查；契约见
   `friend-public.md`，共享边缘接受 TCP 不等于该主机存在服务，不能替换成纯 TCP 扫描。
   失败立即结束，不安装或调用 API。随后验证 MSI 安装退出 0、安装版窗口可见并正常关闭，然后在独立 JVM 中驱动
   同一 commit 的生产 `WindowsTextbookHost`、原生导入核心、配对 UI、逐书同意和
   选段提问。**窗口驱动不是安装版 exe 进程，导入调用核心而非文件选择/拖放手势**；
   这两项限制必须与结果一并披露，不能称为完整的安装版用户操作录制。
   只生成自写样例，不上传任何真实教材。实际讲解走已配置的朋友 API，不用 mock。
   驱动在配对和计费之前另验 8791 TCP 不可达，保留桥端口边界。
6. `friend-windows-evidence` artifact 出现后，先在 Mac 确认 B 的账本恰好一次真实调用，
   对照 input/output token、设备归属及无残留 reservation；格式重试若多调用则测试应
   失败，不能改账本凑数。然后用 `app revoke-friend NAME_A` 撤销 A。
   后续步骤最多等 180 秒，必须得到 A=401、B=200，生成 revocation artifact。
7. 无论 Actions 成败，都在 Mac 撤销 A/B，删除三个临时 Actions secrets，核对其他朋友
   和李政自己的连接仍正常。runner 的 `always()` 只清理 runner 私有文件；它不能替代
   Mac 撤销。不要取消本地控制程序后把清理留给一个不会再醒来的后台任务。

上传白名单仅有自写样例的讲解/出处截图和无身份信息的 JSON。失败不上传窗口、
异常栈、Gradle 测试报告、配置或私有目录。GitHub 日志禁止写地址/代码/令牌；
派发前核对工作流来源。共享的 URL、令牌和 pairing 状态不进入 artifact。

首次临时公网开放前，受管 JVM 须显式设置 `COACH_FRIEND_API_ONLY=1`、
`COACH_TRAFFIC=test` 和三个已审核的隔离端口，才会选择与私有朋友入口相同的
`friend-service-bridge.py`。密钥只由 bridge 子进程读取已有 Keychain 项；
macOS 上仅 `security` 读取子进程的 HOME 使用当前 UID 的账号主目录，避免隔离 HOME
改变 Keychain 搜索列表。JVM、bridge 和资料目录继续使用隔离 HOME，不复制 Keychain。
不带此选项的生产桥接行为不变。实际监听必须核对该选择及朋友选段、模型、工具、
格式和 owner 流量限制，不得拿普通 bridge 或仅 wrapper 单测替代。

本地离线协议演练（先构建 `:server:app:installDist`，使用 bridge venv Python）：
`python tools/smoke/friend-windows-rehearsal.py --work NEW_PRIVATE_DIRECTORY --node NODE_EXECUTABLE --math-dependencies NODE_MODULES_DIRECTORY`。
它启动真实受管 JVM 和上述 wrapper，使用私有 Keychain shim 与离线提供方 SSE，
验证 B→A、撤销、200→429、既有非零 owner 预算和预留连续性，最后关闭三处监听。
所有 HOME/账本/资料均为合成 fixture；这不证明真实 Keychain、费用、Windows 或外网。

## 交付证据

记录候选 head、两个独立审核结论、隔离门禁命令/退出码、切换/五分钟内验证结果、
Funnel 规则数量（不含地址）、Actions 链接、截图、撤销 A/B、B 一次调用的 token
计数和清理结果。协议步骤只验证 80/8443/10000/18791 四个主机路由，驱动另验
8791 TCP；不能写成全部 65535 端口均已验证或所有采样端口均 TCP 关闭。
`ready-for-revoke.json` 的 `bridgePortClosed=true` 只指 8791；主机路由证据来自前置步骤日志，
不再输出旧的 `otherPorts=4`。任一步未跑写「未验证」；工作流文件存在不代表上线完成。
