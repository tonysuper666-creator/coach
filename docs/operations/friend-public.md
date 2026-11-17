# 朋友公网入口的分段上线与撤销

TON-1688 的第一段只交付协议探针和手动 Windows Actions 工作流，不安装客户端，不改生产服务或 Funnel。后续完整上线仍在本卡继续；本文件不是生产切换授权。

已有服务契约见 [FRIEND_ACCESS.md](../FRIEND_ACCESS.md)。公网链路为：外网客户端 → Funnel HTTPS 443 → `127.0.0.1:$COACH_PUBLIC_PORT` → 朋友鉴权与本机资料证明 → API bridge → 朋友独立账本。正常监听 18791、owner 配对、课程和订阅 CLI 不进入这条公网链路。

## 本段交付和验证边界

`.github/workflows/friend-public.yml` 只允许手动运行、main 分支、`friend-public` environment，运行于 GitHub 托管的 Windows 机器；并发串行、不取消正在运行的探针，权限只有 `contents: read`，checkout 固定提交且不保存 GitHub 凭据。没有 PR 触发器、安装包、GUI、截图或 artifact 上传。

探针只接受环境变量中的秘密，只打印固定的检查标签。仅允许根路径、有效证书的 HTTPS `*.ts.net:443`；禁用代理和重定向，避免向代理或跳转目的地发送 bearer。响应大小上限 16 KiB，错误正文和传输异常不回显。

| 模式 | 从 Windows 检查 | 不证明的内容 |
| --- | --- | --- |
| `surface` | 匿名拒绝；单次码配对为 friend；码重放拒绝；在线状态；20 条私有路径对匿名和朋友均为空 404；4 种错误方法拒绝；匿名讲解/取消拒绝 | 原生界面、本地导入、真实教材讲解、额度和清理 |
| `revocation` | 已撤销 A 为 401，另一个测试朋友 B 仍为 200 | 两份凭据是否真属不同朋友，须由 Mac 管理端先核对 |
| 两种模式共同 | 80、8443、10000、18791 的 TCP 拒绝或超时；DNS/路由错误判失败 | 不是全端口扫描，也不能仅凭一次超时证明服务端防火墙配置 |

两种模式都不发送已鉴权讲解，没有资料上传或计费请求。`revocation` 使用私下预置的两份临时凭据，与 `surface` 新配对的设备是独立检查，不声称是同一次原生闭环。

## Actions 秘密传递与清理

先在 GitHub 创建 `friend-public` environment，限制 deployment branch 为 main，配置可用的人工审核保护。仓库管理员与审核者确认要运行的 main head，完成本卡的生产门禁后才写入秘密；不能把本工作流作为自动部署步骤。

| environment secret | 来源和生命周期 |
| --- | --- |
| `COACH_FRIEND_URL` | 私有部署记录中的 HTTPS 根地址；不得放在 workflow input、仓库变量或评论中 |
| `COACH_FRIEND_CODE` | 专用测试朋友的六位单次码；有效五分钟，只在 `surface` 使用 |
| `COACH_REVOKED_CREDENTIAL` | 已吊销测试朋友 A 的完整配对 JSON，必须含 `role: friend`、`deviceId`、`token` |
| `COACH_ACTIVE_CREDENTIAL` | 独立测试朋友 B 的完整配对 JSON；不能使用同一朋友的另一台设备 |

使用 GitHub 的私密 secret 输入界面，或从受限私有文件经标准输入传给 `gh secret set --env friend-public SECRET_NAME`。不在命令参数里放秘密，不开 shell trace，不把配对 JSON 打到终端。只使用临时测试身份，绝不传 owner 的 token、配对身份、李政教材或 API key。

工作流通过步骤的 `env` 传递秘密，不通过 PowerShell 源码插值或命令参数传递。GitHub 会处理 secret 遮蔽；探针在收到配对响应后另行 `add-mask` 设备 ID 和 token，预置 JSON 也拆开遮蔽；地址和主机名分别遮蔽。workflow command 中的百分号、换行和回车转义。不依赖 JSON 整串自动打码来保护字段，也不回显 HTTP headers、响应正文、异常或选中文本。此机制参照 [GitHub Actions secrets 文档](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。

`surface` 用的测试朋友名在 Mac 管理侧预先登记。环境审核和 runner 准备好后再立即签码、更新 secret 并放行，保证五分钟内消费；排队过期就失败，不自动猜码或重试。查清结果、先吊销该测试朋友，再签新码重新跑。

`surface` 新凭据只在进程内存中使用，不保存或导出。`revocation` 的 A/B 由管理者在私有终端配对并将回包保存到权限 0600 的临时文件，核对 `devices` 的 friendId 不同，然后撤销 A、写入两份 environment secrets 再运行。Windows 只拿测试 token，不拿管理权限。公开评论仅记 A401/B200 及运行链接，不记身份值。

**清理在 Mac 管理侧完成，不能依赖 runner 的 finally。** 每次运行前登记全部测试朋友名；成功、失败、超时、取消都执行 `app revoke-friend '测试朋友名'`，逐个以原测试凭据确认 status 为 401，再删除 environment 中的三个临时凭据 secret 和本机临时文件。地址 secret 在测试窗口结束时也删除。不删除账本来恢复额度，不自动解除暂停。记录吊销数量、HTTP 状态和清理退出码；清理失败则保留私有恢复记录，本卡不通过。GitHub 上取消 job 不会撤销服务端凭据。

## 不占 Windows 桌面的隔离门禁

从仓库根、已安装 bridge requirements 的 venv 运行：

```sh
python -m unittest discover -s tools/smoke -p test_friend_public.py -v
python tools/bridge/tests/friend_acceptance.py
```

第二条经 `tools/dev/heavy` 在 Mac 构建，使用临时 HOME、资料目录、独立配置/账本和三个随机回环端口，排除生产端口。真实服务和 bridge 配对 A/B、检查禁用路径、吊销隔离，以及 B 日限为 1 时第一次 200、第二次 429；检查账本 calls=1、input=100、output=20、pending=0，owner AI calls=0。只有供应商 HTTP transport 是离线 SSE。它证明协议和费用边界，不证明真实 API、外网、原生 GUI 或真实教材效果。

探针单元检查覆盖跳转不转发凭据、owner/注入凭据拒绝、失败输出无私密正文、字段遮蔽、响应大小限制、非空 404 不误判通过、吊销的活跃对照及端口探测不确定性。不会访问 Funnel 或生产资料。

## 生产切换前必须补齐的证据

1. reviewer 通过本段及最终候选 head；生产升级另外完成一次 `cross_review`。核对最新 main、正在运行的 release、原 LaunchAgent 与回滚材料；现有共享部署文件由其执行者释放租约后才改，不从本分支覆盖别人的配置。
2. 在私有 staging 下，服务和 bridge 使用同一 `COACH_API_STATE_DIR` 与 `COACH_FRIEND_CONFIG`，保留原 owner 数据和配对；独立端口跑通隔离门禁。对实际安装候选另核对身份、配对、数据兼容和回滚。不能把上面的纯临时目录测试当成生产数据兼容结论。
3. 根据完整的真实请求观察证据确认连续 15 分钟没有用户请求。健康检查成功、进程空闲或日志没有一行都不能替代该证据；观察不完整就不切换。
4. 私有备份当前 release 指向、`local.coach.server` 的 plist/环境及一致性数据快照；验证备份可恢复。只对目标 LaunchAgent 切换，保持原 owner 配置，增加经核对未占用的 `COACH_PUBLIC_PORT`；它必须不同于正常监听、bridge、其他服务端口，永远不能是 18791，且只绑定回环。
5. 从切换起计时，5 分钟内实测李政原有配对、讲课、做题与身份/账本仍正常；准备好私有真实材料和原设备再开始切换。任何失败或超过五分钟，立刻关闭本次新增公网入口，恢复原 release、plist 与环境；如有数据写入，按复核通过的兼容/一致性恢复策略回滚。再检查原有路径。不能仅重启候选或重签 owner token 冒充回滚成功。
6. owner 验证通过后才启用下节 Funnel。若当前原生验收窗口未释放，或回滚/活动证据未齐，就保持未上线，不抢别人的桌面、进程或 GUI 测试资源。

本段未实现或执行生产 switch/rollback 工具，也未变更 `local.coach.server`。切换执行者必须先提交针对当时实际 release 的可复核命令、路径和恢复方案，通过上述复核后执行；不复用旧卡里写死的 release SHA。

## Funnel 命令及撤销

以下为上线执行时的模板，`COACH_PUBLIC_PORT` 来自私有部署配置；先验证变量为独立有效端口。`status` 的原始输出可能含地址，只留私有记录。官方语义见 [Tailscale Funnel CLI](https://tailscale.com/docs/reference/tailscale-cli/funnel)。

```sh
"/Applications/Tailscale.app/Contents/MacOS/Tailscale" funnel status --json
"/Applications/Tailscale.app/Contents/MacOS/Tailscale" funnel --bg --https=443 "http://127.0.0.1:$COACH_PUBLIC_PORT"
"/Applications/Tailscale.app/Contents/MacOS/Tailscale" funnel status --json
```

启用前核对没有别人的 serve/Funnel 配置，有则停在人工复核而不清除；启用后确认仅一条 HTTPS 443 → 朋友回环端口，没有 18791、文件服务、TCP 转发、8443 或 10000。不要把整个 owner 服务交给 Funnel 后再靠路径重写过滤。

收回本次入口使用相同 flags 和目标追加 `off`，再检查 status。`--bg` 的配置跨 Tailscale 重启恢复，因此不能用关闭终端作为停用：

```sh
"/Applications/Tailscale.app/Contents/MacOS/Tailscale" funnel --bg --https=443 "http://127.0.0.1:$COACH_PUBLIC_PORT" off
"/Applications/Tailscale.app/Contents/MacOS/Tailscale" funnel status --json
```

不要用 `funnel reset` 清掉他人的配置。停 Funnel 后仍须吊销测试朋友；吊销不等于删除其资料或费用记录。全局 `paused: true` 只拦新朋友 API 请求，不能替代停止入口、吊销或处理在途响应。

## 后续真实外网闭环与额度

当前 Windows 原生窗口释放、最终 main 冒烟及生产门禁完成后，再在已授权的原生测试窗口安排实际客户端外网闭环，不临时扩展本工作流为 GUI 驱动器。朋友用自己的资料，在逐书同意后把必要选段交给朋友服务；不使用李政教材或配对身份。`tools/smoke/friend-service.md` 是 owner/订阅路径，不能拿其配对、书籍路径或订阅桥充作朋友公网验收。

真实资料不进入公开仓库、Actions secrets、日志或 artifact。本工作流保持无 artifact；原生证据仅在许可的私有测试环境取得。需要公开的图先裁掉文本、书名、地址、身份和凭据，只保留通用成功/错误界面；不上传原始截图、资料目录或日志包。不用合成样书抵充真实教材验收。

真实额度验证在 Mac 先记录 A/B 测试身份与当日账本基线，对其中一个独立朋友设置 `daily_calls: 1`。在获准的真实资料上先确认一次 API 讲解完成，再第二次收到 429 和「今天的讲解额度用完了」。账本按 UTC 日核对该 friend/device 的物理调用、输入/输出 token、费用和预留与供应商 usage 一致，owner 账本无串写；存在格式修复的额外物理调用时按实际用量判定，不把一次界面点击固定等于一次 API。不要清零历史或未知预留强凑结果。

另测 A 吊销立即失效、B 继续可用；最后吊销全部测试朋友并清掉临时 secrets。交付仅报告准确 head、运行链接、实际命令/退出码、脱敏数值和私有证据索引；尚未取得的真实讲解、GUI、费用或生产回滚证据明确写未验证。
