# DeepTutor 迁移只读对账

M0 只生成本机映射候选和 Markdown 对账报告，不创建 Coach 学习库。原目录不打开 SQLite 连接；全部连接只指向拷贝。无需 AI 调用。

```sh
tools/dev/heavy ./gradlew :tools:migrate-deeptutor:test :tools:migrate-deeptutor:installDist --offline --max-workers=2
TOOLS=tools/migrate-deeptutor/build/install/migrate-deeptutor/bin/migrate-deeptutor
"$TOOLS" --dry-run
```

默认源为 `~/DeepTutor`，暂存为 `~/Coach/migrate-staging/<时间戳>/`，输出为 `~/Coach/migrate-out/{mapping.json,report.md}`。可通过 `--source`、`--staging`、`--weights`、`--out` 指定本机路径；真实内容和暂存永远不得进仓库。

每次新拷贝要求 bridge.log 的最后记录和 mtime 都已空闲至少十分钟；缺日志、未来时间、活动或源变化都会拒绝。工具不等待、不停止 DeepTutor，也不自动绕过空闲检查。换一个空闲时段重新运行即可。

拷贝覆盖两个数据库和存在的 WAL（不拷 SHM）、课程、日志、笔记、题库、原类型备份、教材 Markdown。DB/WAL 与这些源文件拷贝前后 size/mtime 必须相同，整个 data 目录的路径/size/mtime 清单也必须相同。暂存中保存真实 `ls -laR` 前后输出和 stat 清单。教材只输出页数与锚点；资料只列清单；不遍历 sealed、答案目录，不跟随符号链接。

可单独捕获供独立验收：

```sh
"$TOOLS" --dry-run --capture-only
"$TOOLS" --dry-run --snapshot "$SNAPSHOT" --out "$HOME/Coach/migrate-out/check-a"
"$TOOLS" --dry-run --snapshot "$SNAPSHOT" --out "$HOME/Coach/migrate-out/check-b"
cmp "$HOME/Coach/migrate-out/check-a/mapping.json" "$HOME/Coach/migrate-out/check-b/mapping.json"
cmp "$HOME/Coach/migrate-out/check-a/report.md" "$HOME/Coach/migrate-out/check-b/report.md"
```

`SNAPSHOT` 使用捕获时打印的目录。不得把它设为 DeepTutor 内部路径。对副本执行 `PRAGMA integrity_check` 后，在单个只读事务内读取；不使用 immutable，避免丢失 WAL。

稳定 ID 包含源文件、表与主键；内容 hash 独立标识修订，同一记录内容变化不会获得第二个事实 ID。JSON 记录使用其原 ID，内嵌 KP/模块使用原 ID。全部 LEGACY 作答永不计入窗口，`legacy_passed` 单独锁存；复习项只有在 passed 集合内才可入队。所有缺证据、缺原类型、缺页码、歧义归属均须保留在报告里，不通过模型补猜。

验收者应重新捕获一份副本，在副本上手工 SQL 核对路径内嵌 KP、mastery_interactions、mastery_learning_evidence、mastery_events、assessment_attempts，并抽十条映射对照源主键。对账基线是设计中的历史时点，之后真实学习新增的数据会成为差异；不得修改源记录以凑齐基线。

独立 SQL 计数在 `verify-counts.sql`；仅对复制库运行 `sqlite3 -readonly`，先把 `mapping.json` 的 `path_id` 设为 `@path_id` 参数，再 `.read tools/migrate-deeptutor/verify-counts.sql`。该文件通过 SQLite JSON 函数直接数源聚合，不使用迁移器的计数结果。另在 chat 副本上核对 `assessment_attempts` 的 `mastery_path_id`、题库 JSON 的 `question_type` 分组和笔记的 `records` 长度。
