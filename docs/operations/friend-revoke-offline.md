# 离线吊销朋友凭据

`tools/dev/friend_revoke_offline.py` 是 [朋友切换方案](friend-cutover-plan.md) 保留的显式维护工具。只操作已存在的 SQLite，不启动服务、不发 HTTP、不改安装记录、Funnel、API state 或费用账本。历史[持久启动门禁](friend-service-guard.md)曾集成本工具，但该方案不再推进。本工具保留为显式维护和测试清理手段；当前回滚隔离依靠朋友凭据哈希域，不以自动吊销作为旧版启动前置。

默认只读检查；只有显式 `--apply` 才写入。写入不可能在 SQLite 的只读连接上进行：工具先以 `mode=ro` 检查，再以 `mode=rw` 重开，且禁止创建新库。它不执行迁移；缺少 `devices.friend_name` 或 `pairing_code.friend_name` 就失败，不能把旧 schema 当作“没有朋友”。WAL 必须正常读取，不使用忽略 WAL 的 `immutable=1`。

## 调用前提与私有身份记录

执行器必须先关闭精确的朋友公网规则，停止目标服务及其受管子进程，确认没有在途写入，并持有独占操作锁直到吊销验证完成及后续旧版启动。SQLite `BEGIN EXCLUSIVE` 在 WAL 模式下只排斥其他写事务，**不证明服务已停止，也不排斥 WAL 读连接**。本工具不接管进程和执行器锁；显式调用它的维护执行器负责这些前置和失败停机，不启用持久启动策略。验证仅操作临时数据库，不对生产执行。

在受控停止后由可信执行器采集并核对唯一数据库的身份，写入权限受限的私有 UTF-8 JSON。字段为：

| 字段 | 内容 |
| --- | --- |
| `database` | 规范的绝对 SQLite 文件路径，不允许符号链接或尚不存在的文件 |
| `device` | 文件 `stat().st_dev` 的整数值 |
| `inode` | 文件 `stat().st_ino` 的整数值 |
| `epoch` | `ledger_meta` 唯一 `singleton=1` 行的非空 epoch |

记录是执行器绑定目标的私有输入，不是工具自行选择数据库的结果。不要从一个未经核对的路径临时生成记录来绕过身份检查。记录与数据库、备份、原配置都留私有目录，不进仓库、评论或 Actions artifact。复制、替换文件或改变 epoch 后，旧记录失效，须重新核对和审查。

以下仅为调用形式；`private-binding.json` 由未来执行器准备，本仓库不提供真实记录。调用本身不是生产批准：

```sh
python3 tools/dev/friend_revoke_offline.py --binding private-binding.json
python3 tools/dev/friend_revoke_offline.py --binding private-binding.json --apply
```

退出码 0 表示本次检查或吊销成功；默认检查的 0 不能代替 `--apply` 结果。正常输出只有整数计数 `active_friends`、`pending_friend_codes`、`revoked`、`invalidated_codes`；检查模式后两项为 0，前两项可以非零。应用成功必须前两项均为 0。失败退出码 1，只输出 `{"failed":1}`，不回显路径、异常、设备身份、配对码、token 或哈希。不要开启 shell trace。`--help` 只显示静态用法。

## 事务保证与失败处理

只读检查通过后，在唯一写事务内重新核验文件身份、epoch、所需表列及 SQLite `quick_check`。不接受凭据表上的未知 trigger。零等待申请 SQLite 独占写事务，冲突即失败；不会杀掉锁持有者或等待它退出。

同一事务按毫秒时间戳更新 `friend_name IS NOT NULL AND revoked_at IS NULL` 的所有设备，包括空字符串朋友标记；保留已有撤销时间。删除所有 `friend_name IS NOT NULL` 的配对码，包括已过期或猜测次数耗尽的码，避免旧版兑换或未来复活。owner 行、owner 待用码及其他字段须逐值不变，朋友设备不删除。事务内校验活跃朋友和待用朋友码均为 0，再以 `synchronous=FULL` 提交；提交后重新以只读连接核对身份、计数和行内容。重复执行不改变已撤销时间。

任一步骤失败均不得启动旧版。提交前失败回滚整个事务；提交后读回失败仍返回失败，即使吊销可能已经提交，也须保持停机并重新核验。禁止恢复旧数据库覆盖吊销；费用账本及切换后的其他写入保持原位。

验证命令：

```sh
python3 -m unittest tools.dev.test_friend_revoke_offline -v
```

测试使用临时 SQLite，覆盖 owner/新写入/预算文件保留、幂等、待用码、WAL 中的新凭据、身份错配、缺列、锁冲突，以及第二条 SQL 失败时第一条更新回滚。旧版 SQL 查询检查仅佐证凭据失效；当前 `tools/smoke/friend-rollback.py` 验证的是不吊销凭据的哈希域兼容，不验证本工具；两者均**不是完整执行器回滚或生产 cross_review**。
