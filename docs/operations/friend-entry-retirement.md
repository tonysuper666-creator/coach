# 历史入口退役的只读准备

`tools/dev/friend_entry_retirement.py` 将经审查选择的历史 Python 入口打成私有 dry-run 包。它没有 apply、恢复或启动命令，不替换任何服务文件，不运行或导入待退役代码。实际退役仍属于生产变更，必须在完整入口清单、停机及持锁方式通过审查后另交可重复执行器。

## 选择与打包

私有 selection 文件权限须为 0600，内容为非空数组，每项只有 `relative` 和 `sha256`。前者是服务目录下的相对路径，后者是审查时采集的完整 SHA-256。仅接受首段以 `upgrade-` 或 `rollback-` 开头的 `.py` 文件；当前控制器、配对脚本、release、数据库及其他文件必须采用各自经审查的迁移流程。所有源路径要求规范、当前用户所有、没有符号链接，不接受重复项和哈希漂移。选择名单不是自动完整性证明，也不代表每项均为已确认旁路。

```sh
python3 tools/dev/friend_entry_retirement.py prepare \
  --root "$COACH_SERVICE_ROOT_PRIVATE" \
  --selection "$COACH_RETIREMENT_SELECTION_PRIVATE" \
  --output "$COACH_RETIREMENT_BUNDLE_PRIVATE"
python3 tools/dev/friend_entry_retirement.py verify \
  --plan "$COACH_RETIREMENT_BUNDLE_PRIVATE/plan.json" \
  --expected-sha256 "$COACH_RETIREMENT_APPROVED_PLAN_SHA256"
```

这些变量只在本机私有环境设置，不写真实路径到 Actions 或公开评论。output 必须为服务树外尚不存在的目录。工具创建 0700 目录，其中 `originals.zip`、`refusal.txt`、`plan.json` 均为 0600。原始脚本可能含秘密或地址，三个文件都不能上传为公开附件。

plan 绑定服务目录 device/inode、每项源文件 device/inode/mode/内容摘要、归档与拒绝桩摘要。verify 必须传入审查记录中的 plan 摘要，重新检查源文件及打包内容；重新从当前 plan 自取摘要不能代替审查绑定。prepare 末尾也执行同一验证，变化导致失败，部分生成的包不能当成功证据使用。正常输出只含文件数、零生产修改及 plan 摘要，失败只输出固定标签，均不打印路径或异常正文。

`refusal.txt` 是供未来执行器使用的 Python 顶层拒绝桩。直接运行、普通 import 或 runpy 均退出失败；单独取消执行位不够。归档只是非代码容器，并非同用户权限隔离，解包恢复仍须审查。

## 仍然阻止生产启用的入口

历史代码的原地替换尚未实现或执行。LaunchAgent 的直接 JVM RunAtLoad/KeepAlive 不经过当前控制器；自动启动的统一 launcher 仍须实现和隔离验证。原生 CLI 直接签码及 shell/动态导入闭包也不能凭这份 selection 排除。当前 release 运维副本的处置不能修改冻结产物后沿用旧摘要。

桌面 `coach-pair.py` 现在仅委托持久控制器 `pair-code`。在策略或锁文件启用门禁时，控制器从就绪检查到签码一直持有同一操作锁，且签码前复核 installation/plist 原始字节。争锁、闭锁和指针漂移均不签码；配对失败释放操作锁并保留已就绪的 owner 服务。尚未部署的新脚本不能证明现有桌面入口已收口；未启用门禁的旧行为仍不提供操作锁保证。

这些代码与 dry-run 包不能代替冻结候选复演、生产 reviewer/独立 cross_review、15 分钟真实空闲、切后 5 分钟 owner 原生回归及外网/真实额度验证。Funnel 永不指向 18791。

```sh
python3 -m unittest tools.dev.test_friend_entry_retirement tools.dev.test_friend_pair_operation \
  tools.dev.test_friend_service_guard tools.dev.test_friend_revoke_offline \
  tools.dev.test_ton1175_switch tools.dev.test_ton1175_supervise -q
```
