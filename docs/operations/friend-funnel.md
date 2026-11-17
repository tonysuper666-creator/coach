# 朋友讲解入口：上线和外网 Windows 验收

仅适用于 TON-1688 已获同意的朋友入口。四条路由及额度契约见
`docs/FRIEND_ACCESS.md`。本文不保存实际地址、tailnet 名、配对码、设备令牌或教材。
不要把私有操作记录或原始服务日志上传到公开仓库/Actions。

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
不会在构建阶段读取朋友密钥。所有 run 都必须来自审核后的同一 commit。

1. 运行 `phase=package`，等 Windows MSI 和 UI 驱动构建成功，记下 run ID。
   安装包是未签名开发包；只在临时 Actions Windows 运行机安装，不在用户设备发布。
2. 在 Mac 私有朋友配置中建立本轮唯一名字 A、B（不得复用已有朋友名），B 的
   `daily_calls=1`。合并配置时保留原字段；先备份，结束恢复时保留期间其他人的修改。
   两个朋友只用于本轮，账本保留，不删除或清零 quota/reserved。
3. 用**当前安装 release 的 app 和当前服务相同环境**分别执行
   `app pair-code --friend NAME`，在本地解析码，不能输出到公有日志。
   通过 `gh secret set` 的 stdin 设置 `COACH_EXTERNAL_URL`、
   `COACH_EXTERNAL_CODE_A`、`COACH_EXTERNAL_CODE_B`，绝不把值放进命令参数、仓库文件
   或 workflow inputs。URL 使用 HTTPS 443 的 Funnel 根地址。
4. 立即手动派发 `phase=probe, package_run=ID`。配对码五分钟过期；队列过久或码过期
   就生成新的码并重跑 probe，不能延长服务端有效期或使用常驻令牌代替一次性码。
   GitHub 在排队时读取 repository secrets（[官方说明](https://docs.github.com/en/actions/reference/security/secrets)）；不要试图在运行中换 secret 修复该 run。
5. Probe 验证 MSI 安装退出 0、安装版窗口可见并正常关闭，然后在独立 JVM 中驱动
   同一 commit 的生产 `WindowsTextbookHost`、原生导入核心、配对 UI、逐书同意和
   选段提问。**窗口驱动不是安装版 exe 进程，导入调用核心而非文件选择/拖放手势**；
   这两项限制必须与结果一并披露，不能称为完整的安装版用户操作录制。
   只生成自写样例，不上传任何真实教材。实际讲解走已配置的朋友 API，不用 mock。
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

## 交付证据

记录候选 head、两个独立审核结论、隔离门禁命令/退出码、切换/五分钟内验证结果、
Funnel 规则数量（不含地址）、Actions 链接、截图、撤销 A/B、B 一次调用的 token
计数和清理结果。端口扫描只覆盖 18791/8791/8443/10000 四个采样端口，不能写成
全部 65535 端口均已验证。任一步未跑写「未验证」；工作流文件存在不代表上线完成。
