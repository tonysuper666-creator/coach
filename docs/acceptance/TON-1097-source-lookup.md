导师按需翻原文的实现与验收

- 追问先经课程内双 FTS（英文 porter、中文 trigram）补页。高分命中与已有带定位符的备课页去重、排序，最多 6 页、40K 字（含定位符）；开讲和批改不走查阅。
- 只有完整回答末尾的 `【需查原文:…】` 能提出一次查阅。补充沿用原消息 ID、序号、落库和续传；查阅中显示“导师去翻原文了”。取消沿用 HTTP 断开到桥杀 CLI 进程组的路径；单调钟 90 秒截止，截断时丢弃未完成段落。
- 补充先过导师输出过滤，再逐段检查来源锚点与逐字引文。未知锚点、缺锚点、非逐字引文均整段不显示；补充不能再递归请求查阅。该任务不调用出题、批改或掌握度写入接口。
- 每个查阅尝试写 `ai_calls.request_type=source_lookup`，包括零物理调用的额度保护、无材料、沙箱不可用等失败。实际模型调用沿用桥返回的调用数；日志记录段落检查数、合格数和耗时。

沙箱审查

已重读 `tools/bridge/sandbox/sandboxed_agent.sh` 与 `agent_sandbox.sb.in`：原 profile 供订阅 CLI 运行，允许 CLI 配置、缓存和桥工作目录读写，不能直接作为只读课程查阅的权限边界。

`SourceReadSandbox` 单独生成严格白名单 profile，由服务端把本课程 `tutor_visible=1` 的候选教材页导出到私有临时目录，沙箱只允许读取这些文件的精确真实路径。`answer_keys`、含“答案”的目录与 sealed 材料在来源筛选时排除；SQLite 账本和原始库不交给子进程。只读 `/bin/cat` 读完后立即删快照，再把文本交给现有禁工具桥调用，模型始终没有文件工具。

profile 默认拒绝，允许系统程序及其装载必需的只读路径和文件元数据；不允许网络、文件写入或读取其他数据。根目录的 literal 读取是 macOS 动态装载所需，不是根目录的 subpath 放行。测试实际启动 `sandbox-exec`，确认普通教材可读、答案目录/无关账本/符号链接/数据卷别名越权读取失败，并确认写文件失败。非 macOS 或沙箱启动失败时不降级成无沙箱读取。

数据库升级到 V005，升级前保留备份，重建已有页的 FTS，并通过触发器维护后续 SQL 导入、更新、删除。已有账本事件不变。

独立复跑

```sh
uptime
tools/dev/heavy env JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew \
  :server:store:test :server:core:test :server:ai:test :shared:client:jvmTest \
  :server:app:test :server:app:sourceReplay --offline --max-workers=2
```

`sourceReplay` 必须有本机教材副本；默认读 `~/Coach/corpus/textbooks`，可用 `COACH_SOURCE_CORPUS` 改路径。结果写到 `~/Coach/corpus/source-lookup/<timestamp>/metrics.jsonl`（可用 `COACH_SOURCE_OUT` 改路径）。仓库不包含教材、真实问题、数据库或原始输出。

回放使用真实教材页与生成的问题，但模型是桩：20 对未触发查阅的首字对比，另有 10 包内/10 包外的受控触发与引用过滤。它验证本地检索、编排和安全过滤，不证明真实导师的触发判断或引用合格率。真实模型 10+10 的触发率、平均耗时、原始引用合格率仍须独立验收者实测；显示段落的校验通过率与模型原始输出合格率须分别报告。

第 2 级当前默认关闭，`COACH_SOURCE_LOOKUP=true` 可在独立验收通过后启用。期中保护截止前（2026-11-02 15:00 GMT）以及桥报告额度保护时仍仅执行第 1 级。不以桩回放替代真实模型 ≥80% 的启用依据。本卡未启动 8791、未调用真实订阅 CLI；DeepTutor 的运行文件未改动。
