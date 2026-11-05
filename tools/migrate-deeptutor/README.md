# DeepTutor 迁移只读对账

## M1-α：只装课程内容（TON-1127）

`seed-content` 是独立于正式迁移的子命令。不导入学习位置、作答、掌握度、复习、笔记、对话、题目或含历史进度的旧课程指令。真实输入和输出必须在仓库、DeepTutor 目录之外。

本节的结构化装载入口以显式 `--database DIR` 选择。main 已有的 `--data` / `--corpus` 入口及仅带 `--snapshot` 的默认路径保持原样，继续生成集成版使用的 packs 和 TON-1095 引用的 `seed-content-manifest.json`。两条路径分别使用自己的输出目录，不在同一已装载库中混用。

```sh
# 今晚至 2026-10-31 08:00 GMT：先确认 pgrep -f GradleWrapperMain | wc -l 小于 2。
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :tools:migrate-deeptutor:test :server:store:test :shared:domain:jvmTest :tools:migrate-deeptutor:installDist --offline --max-workers=6 --console=plain
TOOLS=tools/migrate-deeptutor/build/install/migrate-deeptutor/bin/migrate-deeptutor
# 空闲检查、DB/WAL 拷贝、前后 stat/ls 与 integrity_check 沿用 M0。
"$TOOLS" --dry-run --capture-only --weights docs/background/期中分值覆盖表.md
# SNAPSHOT 设为上一条打印的 Captured 目录。不得对 DeepTutor 原库开连接。
# COACH_DATA_DIR 设为集成服务端的数据目录；默认 ~/Coach/data。必须先停止这个 Coach 服务端。
"$TOOLS" seed-content --snapshot "$SNAPSHOT" --database "${COACH_DATA_DIR:-$HOME/Coach/data}" --weights docs/background/期中分值覆盖表.md
```

08:00 GMT 后构建恢复 `tools/dev/heavy`（nice 15、一次一个），`--max-workers=2`。

数据落在标准的 `coach.sqlite`、`blobs/`，共享服务端 `server.lock`；服务端运行时 CLI 拒绝写入。默认输出为 `~/Coach/seed-content-out/content.json` 与 `report.md`，可用 `--out` 改位置。相同内容哈希跳过，内容修改则在同一事务更新课程和两条内容事件。已有学习事实保持不变。全新库的事件只有 `outline_revised`、`exam_weights_set`。

服务端集成接口：

```kotlin
actor.execute { courseContent(courseId) } // CourseContent：课程、按源顺序的模块与 KP
actor.execute { teachingPages(courseId, kpId) } // List<Pair<SourceAnchor, String>>，备课包原文
```

`outline_revised` 同时携带 `course`、`modules`、`knowledgePoints` 和既有投影支持的 `ops`；客户端同步后可读取标题与结构。教材 Ch1–12 的正文、页号和 FTS 留在本机 SQLite，完整 Markdown 用内容寻址 blobs 保存，不进入事件或仓库。原类型备份缺失的 KP `kind=null`，不把当前松绑后的类型冒充原类型。

锚点优先沿用备课证据中的**教材**坐标（`page_index+1`，校对书页）；其余按 KP 明示章节关联全部章节页。没有章号的 KP 只收至少两个英文标题词在页内命中的页。`basis` 分别为 `SOURCE_REFERENCE` / `CHAPTER_RANGE` / `TEXT_MATCH`，按来源引用与标题词匹配度排序。章节关联是备课上下文，不能当成逐段引用已验证；无锚点逐条写报告。不索引资料、答案、sealed、讲义或 Ch13 以后内容。

分值表本身是估计，有跨 KP 的题目，未给每个 KP 独立权重。保留 `examWeightGroups` 的原始题名、分值、成员和分摊；`examWeights` 按每行成员均分为整数，余数按表中先后分配。RS 30 分在 6 KP 上各 5；联合 Essay 的 15 分为 RAND/Oregon 8、因果识别 7。每行总分不变，总计 100；不是声称原表规定了 8/7。修改表导致归属无法唯一确定会在写库前失败，不猜映射。

验收者在新的离线数据目录自行装两次，再核对：

```sh
"$TOOLS" seed-content --snapshot "$SNAPSHOT" --database "$HOME/Coach/content-check" --out "$HOME/Coach/content-check-a" --weights docs/background/期中分值覆盖表.md
shasum -a 256 "$HOME/Coach/content-check/coach.sqlite"
"$TOOLS" seed-content --snapshot "$SNAPSHOT" --database "$HOME/Coach/content-check" --out "$HOME/Coach/content-check-b" --weights docs/background/期中分值覆盖表.md
shasum -a 256 "$HOME/Coach/content-check/coach.sqlite" # 重装前后相同
cmp "$HOME/Coach/content-check-a/content.json" "$HOME/Coach/content-check-b/content.json"
cmp "$HOME/Coach/content-check-a/report.md" "$HOME/Coach/content-check-b/report.md"
python3 tools/migrate-deeptutor/verify-content.py --snapshot "$SNAPSHOT" --database "$HOME/Coach/content-check" --weights docs/background/期中分值覆盖表.md
```

最后仍需验收者在 DeepTutor 界面和 Coach 抽查 5 KP；CLI 验证不能替代界面对照。

## M0：只读对账

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

组题容器另存为 `legacy_attempt_groups` 并引用已有子题；`attempts` 只计叶子事实，`interaction_rows` 保留原始行数。原类型备份无法按源 ID 找到时，`type=null`，当前源类型保留在 `current_source_type`，由后续事实确认处理。

## M1 考前准备（TON-1095）

`--dry-run` 保留原有纯计划模式；新增 `rehearse` 只在自动创建的 Coach 数据库副本上真实写入。TON-1127 已装载的课程和分值事件只校验复用。最新授权允许立即副本预演；生产导入、激活和本人确认仍须分别验收。

已装载课程的清单来自 TON-1127，不再次创建 Course、Module、KP、教材页或 `exam_weights_set`。把已有课程清单作为历史导入计划的前提：

```sh
"$TOOLS" --dry-run --snapshot "$SNAPSHOT" \
  --seed-manifest "$HOME/Coach/corpus/seed-content-manifest.json" \
  --out "$HOME/Coach/corpus/ton-1095/baseline"
```

先核对已有清单与本次快照的课程 ID、KP 集合、名称、顺序、模块归属，以及分值表总分。再用已有的纯读取 `prepareSeed` 核对逐 KP 分值，实际计划原样引用已验收的课程清单，不重复写入。清单有差异会拒绝生成计划。该核对针对清单；正式导入时还必须核对当前数据库与清单一致。

额外输出（全部只放私有输出目录）：

- `proposal-input.json`：题目、KP 名称及源修订哈希；不包含他的作答或对话，供模型提议与异厂复核。
- `import-plan.json`：引用已有课程的历史导入计划。选择题的结构化选项、答案使用 `optionId`，兼容题库对象格式与历史作答数组格式。无法确定的选项保留原回答文字，结构化答案标未知。
- `simulated-state.json`、`simulation.json`：内存中的稳定源 ID 到记录映射及两次重跑计数。这是**计划模拟**，不是正式数据库迁移验收；修订替换同一源记录，不增加第二份作答。
- `confirmation.md`：一页大白话确认入口；`confirmation-details.md`：逐题归属、共享概念、前置建议及跨章节抽取的 10 道简答源采分点。两份都标为草稿。

消息仅留下正文及显示所需的 ID、角色、时间、会话/父消息关联；丢弃 `metadata_json`、`events_json`、附件和隐藏模型回合。45 项原类型缺失的数据保留 `type=null` 与原 `current_source_type`。LEGACY 永不进窗口；复习仅在原已通过集合内有入队资格。题库题的 `keyState` 一律 `UNREVIEWED`，计划中正式题目归属为空，模型建议单列；模型复核不等于答案键验证或本人确认。

### 提议与异厂复核文件

模型先基于 `proposal-input.json` 提议，再交另一厂商核对完整原题/原 KP 名称。提议文件结构如下（仅合成字段示意）：

```json
{
  "input_hash": "sha256 of canonical proposal-input.json",
  "proposer": {"model": "model-name", "vendor": "vendor-a"},
  "concepts": [{"id": "concept:synthetic", "name": "合成概念"}],
  "knowledge_points": [{"id": "synthetic-kp", "concept_ids": ["concept:synthetic"], "prerequisite_ids": []}],
  "questions": [{"id": "legacy:synthetic:questions:q", "kp_ids": ["synthetic-kp"], "reason": "实际考点", "warning": "待核对事项"}],
  "review": {"model": "review-model", "vendor": "vendor-b", "status": "reviewed", "proposal_hash": "sha256 of canonical proposal excluding review"}
}
```

哈希使用 `canonical()`（按键排序、四空格缩进、UTF-8、末尾换行）。`review` 记录最终提议的复核结果；保留模型原答与修改说明作证据，不靠自填厂商字段证明完成复核。输入哈希绑定题库顺序和内容、KP 内容；修改提议使复核失效。工具检查每题/每 KP 完整覆盖、引用存在、共享概念存在、前置无环。复核不能自动批准学习事实。

```sh
"$TOOLS" --dry-run --snapshot "$SNAPSHOT" \
  --seed-manifest "$HOME/Coach/corpus/seed-content-manifest.json" \
  --proposal "$HOME/Coach/corpus/ton-1095/proposal-reviewed.json" \
  --out "$HOME/Coach/corpus/ton-1095/repeat-a"
```

换另一个 `--out` 重跑，使用 `cmp` 比较所有 JSON 和 Markdown。输出目录必须与快照分离，不能用输出覆盖快照。真实提议、复核原答、确认明细都不得提交公开仓库。

独立验证器使用 Python 标准库直接读取快照 SQL/JSON，对照已通过集合、原类型、全部旧作答的选项转换、消息正文和八份输出的字节一致性：

```sh
nice -n 15 python3 tools/migrate-deeptutor/verify-plan.py \
  --snapshot "$SNAPSHOT" --seed "$HOME/Coach/corpus/seed-content-manifest.json" \
  --out "$HOME/Coach/corpus/ton-1095/repeat-a" \
  --repeat "$HOME/Coach/corpus/ton-1095/repeat-b"
```

可在本轮开工前对源 data 用 Python `lstat` 记录 `{相对路径: [size, mtime_ns]}`，之后传 `--before-inventory FILE` 核对运行期间源目录不变（只 stat，不对源开库）。私有 JSON 不得进仓库。验证器输出仅含计数和哈希，完整原文留在本机。

## 隔离副本真实导入与回滚验证

只读打开现有 Coach 数据库，以 SQLite online backup 捕获已提交的 WAL，生成 `before.sqlite`；再复制为 `coach.sqlite`。所有写入都发生在新输出目录的 `coach.sqlite`，没有生产导入开关。命令拒绝复用输出目录、覆盖快照或在运行中的数据目录下生成副本。

```sh
tools/dev/heavy "$TOOLS" rehearse \
  --snapshot "$SNAPSHOT" --coach-db "$HOME/Coach/data/coach.sqlite" \
  --seed-manifest "$HOME/Coach/corpus/seed-content-manifest.json" \
  --proposal "$HOME/Coach/corpus/ton-1095/proposal-reviewed.json" \
  --out "$HOME/Coach/corpus/ton-1095/writer-rehearsal"

nice -n 15 python3 tools/migrate-deeptutor/verify-import.py \
  --snapshot "$SNAPSHOT" --seed "$HOME/Coach/corpus/seed-content-manifest.json" \
  --out "$HOME/Coach/corpus/ton-1095/writer-rehearsal"
```

副本已有的 KP 名称、顺序、模块及最新 `exam_weights_set` 必须与清单相同，否则拒绝。导入采用单事务和稳定源 ID：`migration_records` 保留最新原值与内容哈希，`migration_batches` 按课程及批次 ID 保存 manifest、快照时间和该批真实审计时间。相同来源跳过，变化来源追加一个 `legacy_source_revised`，新来源正常首次导入。修订事件保留来源 ID、前后内容/映射哈希、前后原值和快照时间；原始事件不修改。正式写库和切换入口仍未开放。

- 题库原样保留全部选项，五选项题不截断；全部题 `LEGACY/UNREVIEWED`，不写 `question_kp`、答案键或题池。解析中的独立选项字母替换为选项正文，原文仅存档；仍须逐题盲审。
- 历史作答发出 `legacy_attempt` 和 `answer_submitted`，重置作答另发 `attempt_voided`；不发评分事件。独立题目身份、指纹与空证据 KP 集合保证不消耗原生首答资格，不进入学习窗口。无法确定的自由回答保留原文与 null 结构化答案，确认清单附原选项上下文。
- `legacy_passed` 不提升 solid；复习只接收原已通过集合，原始复习状态完整保留；已有 Coach 复习安排优先，冲突的历史安排只存档。队列按 Europe/London 的 04:00 学习日换算，间隔取上次复习到到期的学习日差（至少一天），ease 从新调度器默认 2.3 开始，lapses 保留。
- 笔记和有内容的旧日志有各自历史事件；旧事件只发 `legacy_imported` 档案引用，不重放旧评分或教学事件。历史消息使用独立 LEGACY 会话，仅保留正文和显示关联字段。

历史事件的 `occurredAt` 与导入墙钟无关：已通过和复习优先使用 `original_state.last_review_at`，其次为该 KP 的 evidence 创建时间、mastery 更新时间；其他事实使用来源的 `created_at` / `updated_at`（秒或带时区的 ISO 时间），日志使用来源日期的伦敦中午。没有可用时间时使用 `copy-stats.json.before` 中最新的已捕获源文件 mtime；晚于这个快照截止时间的来源值不参与选择。缺失 WAL 的 `absent` 不作为时间。该依据写入 `history_time`，原值保持不变，导入时钟早于快照则拒绝。历史作答的 `posedAt`、消息创建时间采用相同历史时间。

`first-state.json`、`second-state.json` 纳入全部业务表行、schema 和实际领域投影（SQLite 内部记账表除外），必须完全相同。计算状态哈希时，仅把 `migration_batches.recorded_at` 和 `deeptutor-migration` 事件的 SQL `recorded_at` / JSON `recordedAt` 归一化为 0；数据库仍保留实际审计时间，原生事件时间完全保留。因此同一基线、快照、计划在不同导入日也得到相同状态/投影哈希。`rehearsal.json` 记录哈希和实际事件计数；跨预演比较须先核对基线和计划一致。

另从 `before.sqlite` 生成 `rollback.sqlite`，验证逻辑状态逐字节恢复基线，绝不覆盖生产库。独立 Python 验证器直接读取快照 SQL、原题 JSON 和三份 Coach 副本，核对课程复用、逐条历史记录、历史发生时间、全部业务表的审计时间归一化、导入日没有增加 legacy 通过/作答/笔记、事件隔离、首答身份、现有 pending/证据不变及回滚结果。输出目录含完整学习库副本和历史原文，必须保持私有，不得整目录附到公开 PR 或评论。

## 隔离副本的连续增量

`rehearse-incremental` 从在线库只读备份开始，在新目录内运行初始导入和两轮连续增量，每阶段均重复导入并保存回退副本。每批有独立的 `recorded_at >= snapshot_at`，同批重导保留该批审计时间；旧单批表会在副本事务内升级。不同批次的 manifest 可以改变。来源删除、来源跨课程/种类变化、已激活题目修订、消息跨会话移动、原值未变而派生内容改变均拒绝并回滚，留给显式核对。

领域投影版本升级为 5：每个来源只回放最新历史事实，修订审计事件仍留在不可变账本。普通修订放回原事实的位置，保留之后的原生复习操作；后续快照新增通过时，刚获得资格的旧复习安排放在通过事实之后。题干/选项和消息正文同时更新各自实体表；作答、笔记、日志及复习更新可见投影，不能只数审计事件。历史作答仍不成为学习证据。没有原始 ID 的 JSON 项继续使用捕获时的位置键，**重排前必须人工确定 ID 对照**，不能把重排当内容修订直接导入。

旧提议只对已复核的原输入有效。题目或 KP 内容变化后，严格校验保持原样，旧提议以 `stale_quarantined` 存档，新计划不沿用其归属或复核资格。全部历史题仍为 `LEGACY/UNREVIEWED`，不写题目归属、答案键或题池。`confirmation-details.md` 合并 108 题（107 有归属提议、1 未知）、49 KP 的概念/62 条前置、10 道简答源评分依据、两条 B/c 未解析历史回答、60 道五选项及解析字母替换、9 个通过时间的近似说明。每项附来源、建议与依据；这是待核对草稿，不自动发起本人确认。没有独立 rubric 的题用源 explanation 作草案，未推断分值或认证评分键。

```sh
SNAPSHOT="$HOME/Coach/migrate-staging/2026-10-31T07-41-31.131462Z"
SEED="$HOME/Coach/corpus/seed-content-manifest.json"
PROPOSAL="$HOME/Coach/corpus/ton-1095/proposal-reviewed.json"
# NEXT / SECOND 必须是已捕获的副本；OUT 必须为私有语料目录内的新目录。
JAVA_HOME=/opt/homebrew/opt/openjdk@17 tools/dev/heavy tools/migrate-deeptutor/build/install/migrate-deeptutor/bin/migrate-deeptutor rehearse-incremental \
  --snapshot "$SNAPSHOT" --next-snapshot "$NEXT" --second-snapshot "$SECOND" \
  --coach-db "$HOME/Coach/data/coach.sqlite" --seed-manifest "$SEED" --proposal "$PROPOSAL" --out "$OUT"
nice -n 15 python3 tools/migrate-deeptutor/verify-incremental.py \
  --snapshot "$SNAPSHOT" --next-snapshot "$NEXT" --second-snapshot "$SECOND" --seed "$SEED" --out "$OUT"
```

验证器独立读源 SQL/JSON 和输出 SQLite，核对旧账本不变、每个修订的哈希链、题目/消息实体、最新有效作答/笔记/日志与复习投影、原生状态、重复导入及逐字节回退。`make-incremental-fixture.py --snapshot "$SNAPSHOT" --out "$FIXTURE"` 可在全新私有目录复制并构造两轮测试输入，写入 `SYNTHETIC-INCREMENTAL-FIXTURE.json`；它不是新捕获证据，不能证明正式来源的增量已同步。

## 正式迁移仍待验收

固定快照预演不能证明源之后的新数据已同步。独立 QA-C 验收、随后异源复核通过后，把归属/学习顺序与 10 道简答源采分点合成一次本人事实确认；未确认的题不计入任何层。原类型不猜，缺证据的数据保留未知。仍须完成新快照增量、5 个 KP 界面对照、blobs 备份与正式切换/恢复验证，不能因工具 PR 合并而结束整张迁移卡。

切换不放在考试前 7 天内；连续两次增量对账零差异后再切换。切换前记录备份和课程清单哈希。切换后 DeepTutor 原样只读可开，保留至少两周；需要回退时停止 Coach 写入、保存 Coach 切换后的新增记录，重新打开 DeepTutor，必要时恢复切换前 Coach 备份。不得把 Coach 数据反写 DeepTutor，也不得删除源数据。正式切换/备份恢复尚未演练。
