受管朋友入口的本机隔离演练使用 `tools/dev/rehearse_friend_managed.py`。它不安装生产服务，不设置 Funnel，不创建启动策略，也不调用模型。真实资料、API 费用、Windows、外网、完整指针事务和生产回归仍需各自验收。

受管测试实例须显式设置 `COACH_TRAFFIC=test`、`BRIDGE_PORT` 和匹配的 `COACH_BRIDGE_URL=http://127.0.0.1:端口`。端口须在 1024–65535，不能是 8790、8791、18791、3790、19540，也不能与正常或朋友监听相同。默认和 `user` 流量仍仅允许受管 bridge 使用 8791；独立测试实例不能借用生产 bridge。子服务仍在绑定前检查占用，不接管或杀掉已有监听者。

先从干净提交构建独立 release，按 `ton1175_verify.py attest` 验证并绑定目录、依赖、JDK/Node 和 source head。release 不能搬迁。另写本人所有、0600 的私有 JSON 文件，字段恰为 `release`、`manifest`、`manifestSha256`；其中路径和清单不进入仓库、评论或 artifact。然后在前台运行：

```sh
python3 tools/dev/rehearse_friend_managed.py --config PRIVATE_CONFIG --receipt NEW_PRIVATE_RECEIPT
```

工具为本轮生成独立 HOME、数据、日志、状态和三个随机回环端口。它仅在私有控制器副本中替换 `LABEL` 常量，以免触及 `local.coach.server`；`start`、`ready`、`pair-code`、`status`、`stop` 的实现不替换，全部由独立进程调用。`tailHost` 设为回环，因此控制器两次认证探测都只证明本机连通，不能冒充 tailnet 结果。PATH 中的 `security` 拒绝桩阻断 bridge 启动脚本读取 Keychain；不注入生产密钥，配对码和令牌只存临时私有目录。

验收覆盖：bridge/math 的新鲜健康和 JVM 所有权、重复 start 与签码不重启 JVM/子进程、朋友配对、匿名拒绝、20 条私有路径与 4 组错误方法的空 404、撤销 A 后 401 而 B 仍 200、只终止本 JVM 的 bridge 子进程后自动恢复、owner 继续认证、停止后 job/健康文件消失及三个端口释放、候选目录字节不变。此处状态接口及撤销是本机协议证据，不含讲解或额度验证。

工具前台收齐结果后停止自己的 job，移除自己的 plist 并清理临时身份。成功只打印固定 `MANAGED_REHEARSAL=PASS`；失败退出 1，私有回执写失败阶段，诊断留在权限受限的临时目录，路径只写入回执旁的 `.diagnostics` 文件。未确认清理成功不能继续上线。公开报告只取 head、计数和布尔结论，不上传控制器原始日志、诊断目录或凭据。
