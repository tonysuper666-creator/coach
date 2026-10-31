结果：部分达到；真实副本核对 49 KP、108 题、32 条作答事实（29+3 重置）、9 条复习、12 条笔记、9 个已通过；原类型仍有 45 项缺少备份依据。

2026-10-29 21:37 UTC 在桥连续空闲超过十六分钟后捕获副本。两个 DB/WAL、课程、日志、笔记等源文件的前后 size/mtime 一致；data 的 stat 清单与真实 `ls -laR` 前后相同；两个副本 `integrity_check` 均为 ok。真实内容与完整目录证据只留本机私有目录。

| 项目 | 设计基线 | 真实副本 | 差异说明 |
|---|---:|---:|---|
| KP / 模块 | 49 / 12 | 49 / 12 | 一致 |
| 题目（单选/简答） | 108（60/48） | 108（60/48） | 一致 |
| 作答事实 | 20 | 32 | 源继续学习；与 32 条 attempt.recorded 事件、32 条 assessment_attempts 对上 |
| 当前保留证据 / 重置 | 17 / 3 | 29 / 3 | 3 条原 KP 已移除且当前证据缺失；独立 SQL 计数一致 |
| 复习 / 已通过 | 5 / 5 | 9 / 9 | 新学习使两者各增加 4；全部复习 KP 均在 passed 中，排除项 0 |
| 笔记 | 12 | 12 | 一致 |

源有 34 条已作答 interaction 行，其中 2 条是组题容器，各自的子题已有独立行。容器单独存档并引用子题，避免重复计数。全部 32 条 LEGACY 作答及 2 个容器的 window_eligible 均为 false；不写正式学习库。

原类型备份有 46 个键，只与当前 49 KP 中的 4 个 ID 匹配。其余 45 个 `type=null`、单独保留 `current_source_type`，不会把松绑后的 memory/procedure 伪装成原类型；这些项需后续确认。未按名称猜测类型。

108 题确定性自动归属为 **0/108（0%）**：均能缩到章内候选，但仍有 3–6 个候选，题库无显式模块/同文档页码依据。108 项全部列为需模型/人工确认，M0 未调用模型。源题库的 108 题均无原始 ID，以数组位置作键，重排前必须人工核对。

另有教材 24 文件 / 548 页标、期中分值 10 行、资料待确认清单 19 项、会话 1 个 / 108 条消息、源事件 242 条。日志 records 数为 0，其余日志元数据仍存档。资料不进导师上下文，受限目录不遍历。

本机运行：

```sh
tools/dev/heavy ./gradlew :tools:migrate-deeptutor:test :tools:migrate-deeptutor:installDist --offline --max-workers=2 --console=plain
```

退出码 0，8 tests / 0 failures / 0 skipped。新增到合成完整流程的断言验证组题不重复计数、缺失原类型保留未知。

对同一副本用 `--dry-run --snapshot "$SNAPSHOT" --out "$HOME/Coach/migrate-out/repeat-a"` 和 repeat-b 各跑一次，均退出 0；mapping.json 和 report.md 字节一致。两次运行期间对 data 的 7,932 个目录/文件条目 stat 对比相同，真实 ls 输出相同。另一次候选检查碰到 system 目录更新时间变化，未算通过；上述证据来自随后稳定窗口。

独立 Python/SQLite SQL 逐项核对全部主计数；直接抽 10 条源 interaction，与映射 source_record 逐字段一致。仓库内 `verify-counts.sql` 可独立复跑，实际计数通过 SQLite JSON 函数计算，不取迁移器的计数结果。

物理 AI 调用数：0。PR 不包含真实课程内容、学习原文、目录清单或数据库。

## TON-1095 考前 dry-run（2026-10-31）

结果：部分达到；521 条历史计划记录重复模拟，第二次新增 0、修订 0。正式学习库写入 0；正式导入适配器、本人确认与 UI 对照尚未完成，不作为本阶段已通过的验收。

新的空闲窗口快照实测：49 KP、108 题（60 单选/48 简答）、32 条作答（29 保留证据/3 重置）、9 个已通过/9 条复习、12 条笔记、242 条事件、108 条消息。源目录本轮实际 7,929 个条目的 size/mtime 前后一致；历史报告的 7,932 不是本轮实测数。拷贝内两库 integrity_check 均 ok，捕获时 DB/WAL stat 与 data 的 stat/ls 前后相同。

模型提议由 GPT 起草，Claude Sonnet 5.5 两次物理调用异厂复核。按模型的明确修改建议整理最终文件，保存原始答复与修改说明；不把模型意见当本人确认或答案键验证。108 题逐题审查后，107 题有提议、1 题因现有纲要缺口保留未知；49 KP 有共享概念集合，62 条建议前置无环，确认数仍为 0。教材不同章节的两张 Oregon 原表本来数值不同，核对各原表后保留各自数值并注明，不改源题。

历史数组选项和 `expected_answer` 已规范为 `optionId`。32 条历史作答全部永不进窗口；其中 2 条自由表达无法确定性转成选项，结构化答案 null、保留原回答文字。45 个原类型未知项逐项保留；108 条消息正文逐条与源副本一致，不带模型回合元数据。课程内容与分值引用 TON-1127 清单，内容新增 0；合入并发更新的 main 后复用其纯读取 `prepareSeed` 核对逐 KP 分值，仍只核对清单与快照，不宣称已对当前正式数据库完成迁移。

本机命令：

```sh
tools/dev/heavy ./gradlew :tools:migrate-deeptutor:test :tools:migrate-deeptutor:installDist --offline --max-workers=2 --console=plain
"$TOOLS" --dry-run --capture-only --weights docs/background/期中分值覆盖表.md
"$TOOLS" --dry-run --snapshot "$SNAPSHOT" --seed-manifest "$SEED" --proposal "$PROPOSAL" --out "$OUT_A"
"$TOOLS" --dry-run --snapshot "$SNAPSHOT" --seed-manifest "$SEED" --proposal "$PROPOSAL" --out "$OUT_B"
nice -n 15 python3 tools/migrate-deeptutor/verify-plan.py --snapshot "$SNAPSHOT" --seed "$SEED" --out "$OUT_A" --repeat "$OUT_B" --before-inventory "$BEFORE"
```

全部退出 0。Gradle：15 tests / 0 failures / 0 errors / 0 skipped。独立验证器直接读复制库与 JSON，对照全部 32 条作答、108 条正文、已通过/复习集合、45 个原类型缺失项；八份输出逐字节一致。完整快照、提议、确认草稿与复核答复留本机私有语料目录；计数及哈希报告随 Multica 交付。

11 月 3 日之后仍需：新快照、把归属/前置与 10 道简答采分点合并向本人确认、正式写库适配器、备份与真实重导、5 个 KP 界面对照、切换/回退演练。不能因本工具 PR 合并而结束整张迁移卡。
