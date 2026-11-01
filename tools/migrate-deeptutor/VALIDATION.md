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

## 真实副本 writer（2026-11-01）

本节为 PR #52 的历史证据，已被下节时间修复取代。Architect 独立复核发现旧版本用导入墙钟补缺失的历史时间，导致当天错误新增 9 个通过；这里的旧状态哈希只能证明同一批次重导幂等，不能比较独立预演。两次旧预演基线实际相同。

结果：部分达到；在现有 Coach 数据库的隔离副本内实际执行两次事务导入，第一次 521 条历史记录 / 556 个事件，第二次 0 条 / 0 个。全库规范化状态逐字节相同。11 月 1 日 21:00 的本人指令已取消旧日期门槛，上节的日期限制仅为历史记录。尚待独立 QA-C 验收与后续异源复核，不能视为整个迁移卡完成。

从在线 Coach 数据库以只读 SQLite backup 捕获基线，再写入新私有目录内的副本。基线 135 个事件，导入后 691 个。独立 Python/SQLite 检查源快照、三份 Coach 副本及领域回放，实测如下：

| 项目 | 实测 |
|---|---:|
| 源 KP / 题 / 作答 | 49 / 108 / 32 |
| legacy_passed / 复习 / 笔记 | 9 / 9 / 12 |
| 仅存档的旧事件 / 消息 | 242 / 108 |
| 日志内容 / 日志元数据 | 0 / 1 |
| 保留未知类型 / 五选项题 / 未解析回答 | 45 / 60 / 2 |
| 第二次未变化的历史记录 | 521 |

108 题保持 `LEGACY/UNREVIEWED`，未创建归属、答案键或题池记录；未确认的提议仅存档。32 条历史作答有独立题目身份和指纹，不占用原生首答资格、不计入证据；3 条重置另发撤销事件。9 个旧已通过在回放中生效，9 条复习原值保留，12 条笔记进入历史回放。242 个旧事件没有作为现役评分或教学事件重放。消息正文与源逐条相同，隐藏模型回合元数据未留存。

已有课程内容和全部非迁移业务表保持相同，已有题目/会话/消息行逐字段保留；原生学习事件、证据、复习、pending 与 `exam_weights_set` 不变。全库状态与 schema 纳入幂等比较；三份库 integrity_check 均为 ok。回退副本全部表行和规范化状态与基线一致；仅验证私有副本恢复，未覆盖生产库。

本机实际命令（均退出 0）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 tools/dev/heavy ./gradlew :tools:migrate-deeptutor:test :tools:migrate-deeptutor:installDist --offline --max-workers=2 --console=plain
SNAPSHOT="$HOME/Coach/migrate-staging/2026-10-31T07-41-31.131462Z"
SEED="$HOME/Coach/corpus/seed-content-manifest.json"
OUT="$HOME/Coach/corpus/ton-1095/writer-2026-11-01-b"
JAVA_HOME=/opt/homebrew/opt/openjdk@17 tools/dev/heavy tools/migrate-deeptutor/build/install/migrate-deeptutor/bin/migrate-deeptutor rehearse \
  --snapshot "$SNAPSHOT" --coach-db "$HOME/Coach/data/coach.sqlite" \
  --seed-manifest "$SEED" --proposal "$HOME/Coach/corpus/ton-1095/proposal-reviewed.json" --out "$OUT"
nice -n 15 python3 tools/migrate-deeptutor/verify-import.py --snapshot "$SNAPSHOT" --seed "$SEED" --out "$OUT"
```

Gradle：24 tests / 0 failures / 0 errors / 0 skipped。另验证在线 backup 包含已提交 WAL、源连接拒绝写入、源修订冲突整笔回滚、已有复习和待答保留、历史首答隔离。首次构建与首次实际预演曾失败，已修复排序数值类型、五选项误报及标量原值处理；首次预演事务回滚，未将其算作成功。上轮曾等待重构建锁并取消自己的等待进程，本轮锁释放后正常执行，未绕过全机锁。

成功预演的 SHA-256：

- 基线及回退规范化状态：`1fc6b8d43cdb61ef14b6b2d2c1460b3556f94fa31cc54ce1099ba007fc50da9d`
- 首次与第二次全库状态：`21242ce4443cfbd7f13f4ae633a648c61dfc47102f008d4d1ed32a969a613916`
- 实际领域投影：`5aa553515837bc1570ac1d852ae54abc54f0bacd1eecdc6ff4d47479a936fcb9`

完整副本、原始学习数据和全表 state JSON 仅留私有语料目录；平台附件只交付计数与哈希。生产库仅只读 backup，未重复 seed、未激活、未访问 DeepTutor 源或 8790。新增付费模型调用 0；卡内既有归属复核物理调用 2。

正式导入、增量源修订、本人一次合并确认（归属/前置及 10 道简答采分点）、五个 KP 界面对照、blobs 备份与正式切换仍未完成。当前 writer 遇到源修订会拒绝并回滚，不宣称已经支持正式增量替换；不能因工具 PR 合并而结束整张迁移卡或解锁 TON-1102。

## 历史发生时间与跨预演状态哈希修复（2026-11-01）

本节为已合并 PR #53 的历史证据；QA-C 独立复跑和 Architect 后续复核已通过。下节记录新的增量切片，不能把本节通过延伸为整卡完成。

结果：本切片本机验证达到；26 tests / 0 failures / 0 errors / 0 skipped。原有 24 项断言保留，新增两项分别用同一基线的两份副本在相隔两天的注入时钟下导入并比较规范化状态哈希，以及检查导入日没有 legacy 事实、通过/复习使用上次复习时间、ISO 笔记时间和无时间记录的快照回退时间。尚待 QA-C 独立复跑及 Architect 复核。

`occurredAt`、作答 `posedAt`、消息创建时间均由来源或快照确定。计划保存 `history_time`，不使用导入墙钟补历史事实。实际库仍保留真实审计时间；状态输出仅归一化迁移批次及迁移事件的审计时间，原生事件不变。独立验证器从 SQLite 重建规范化业务表，逐条核验历史时间，并检查导入日通过、作答、笔记没有增加。

实际执行命令（以下全部退出 0；两份输出目录此前不存在）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 tools/dev/heavy ./gradlew :tools:migrate-deeptutor:test :tools:migrate-deeptutor:installDist --offline --max-workers=2 --console=plain
SNAPSHOT="$HOME/Coach/migrate-staging/2026-10-31T07-41-31.131462Z"
SEED="$HOME/Coach/corpus/seed-content-manifest.json"
for suffix in a b; do
  OUT="$HOME/Coach/corpus/ton-1095/writer-time-2026-11-01-$suffix"
  JAVA_HOME=/opt/homebrew/opt/openjdk@17 tools/dev/heavy tools/migrate-deeptutor/build/install/migrate-deeptutor/bin/migrate-deeptutor rehearse \
    --snapshot "$SNAPSHOT" --coach-db "$HOME/Coach/data/coach.sqlite" \
    --seed-manifest "$SEED" --proposal "$HOME/Coach/corpus/ton-1095/proposal-reviewed.json" --out "$OUT"
  nice -n 15 python3 tools/migrate-deeptutor/verify-import.py --snapshot "$SNAPSHOT" --seed "$SEED" --out "$OUT"
done
```

首次测试曾因把缺失 WAL 的 `absent` 当作时间解析而失败，修正后全量测试通过。首次独立验证曾因嵌套 JSON 字段顺序影响 Python 的行排序而失败；逐 ID 比对证实实际行差异 0，改为递归按键排序后完整检查通过，未减少检查内容。

两个真实导入时钟分别为 `1793572191054` 和 `1793572241913`。两份基线、mapping、计划、首次状态、二次状态分别逐字节相同；各自回退 SQLite 与基线逐字节相同。两次都首次写入 521 条记录 / 556 个事件，二次 0 / 0，事件总数 135 → 691。保留 45 个未知类型、60 道五选项、2 条未解析回答；课程内容、原生学习/复习/pending/分值不变。日志只增加源历史日 `20754`、`20755`，不再产生错误导入日 `20758`，该日新增通过/作答/笔记均为 0。

- 基线和回退状态 SHA-256：`1fc6b8d43cdb61ef14b6b2d2c1460b3556f94fa31cc54ce1099ba007fc50da9d`
- 两份预演的首次及二次状态 SHA-256：`15c24b6461b475670da0522122a538f1a7a32577006db6a643be1b73c9776d3d`
- 两份领域投影 SHA-256：`0a3352d15add5bc0152a03c92e09f97aafa772be4edeb5dfc17bf0a1bfc33858`

所有实际数据和全库输出仅留私有语料目录；公开交付仅计数/哈希。生产只读备份，无 seed、激活、DeepTutor 源或 8790 访问，无新增模型调用。正式增量修订协议及其批次审计时间问题仍留下一切片，整卡不结束，TON-1102 不解锁。

## 连续增量修订与一次确认材料（2026-11-01）

结果：部分达到。工具支持同一源 ID 的内容修订、每批独立审计时间及两轮副本增量；初始 521 条 / 556 个事件，第一轮 1 条修订 / 1 个事件，第二轮 5 条修订及 1 条新增 / 6 个事件，各阶段重导均为 0/0/0。增量输入是从固定快照构造的测试夹具，**没有重新捕获正式源，不能据此认定两次正式增量对账已完成**。待 QA-C 在新目录独立验收，再交 Architect 复核。

变更包括领域投影与实体表：不可变账本保留原历史事件，每个变化来源只追加一条带前后哈希/原值/快照时间的修订；投影替换该来源的历史事实，题目和消息分别更新其可见实体。原生学习操作继续优先；后续首次通过使原已存档的复习获得资格时，复习在该通过之后回放。投影版本 4 → 5，防止使用旧缓存。已复核提议的输入绑定未放宽；题目/KP 变化则归档旧提议且整体保持未确认隔离，不能把旧复核用于新内容。

本机门禁实际执行：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 tools/dev/heavy ./gradlew :tools:migrate-deeptutor:test :tools:migrate-deeptutor:installDist :shared:domain:jvmTest :server:store:test --offline --max-workers=2 --console=plain
```

迁移工具 35 项、领域层 49 项、存储层 26 项，均 0 失败、0 错误、0 跳过。原 26 项迁移测试保留，新增覆盖单源修订、两轮多种历史事实修订、独立批次/跨时钟状态、旧批次升级、源删除/时钟倒置回滚、编码主键哈希、后续原生复习优先、旧提议失效隔离及新增通过后的复习。一次新测试因重复把毫秒乘以 1000 导致预期错误，已修正单位后重跑；没有放宽原测试。首次夹具和首次 writer 预演也曾失败，分别修复了源选项数组读取与 SQLite 内容哈希中既有主键标记的重建；失败目录未算作成功。

实际命令如下（输出目录均为新建，副本和完整状态不公开）：

```sh
SNAPSHOT="$HOME/Coach/migrate-staging/2026-10-31T07-41-31.131462Z"
SEED="$HOME/Coach/corpus/seed-content-manifest.json"
PROPOSAL="$HOME/Coach/corpus/ton-1095/proposal-reviewed.json"
FIXTURE="$HOME/Coach/corpus/ton-1095/increment-fixtures-2026-11-02-b"
nice -n 15 python3 tools/migrate-deeptutor/make-incremental-fixture.py --snapshot "$SNAPSHOT" --out "$FIXTURE"
for suffix in d e; do
  OUT="$HOME/Coach/corpus/ton-1095/writer-increment-2026-11-02-$suffix"
  JAVA_HOME=/opt/homebrew/opt/openjdk@17 tools/dev/heavy tools/migrate-deeptutor/build/install/migrate-deeptutor/bin/migrate-deeptutor rehearse-incremental \
    --snapshot "$SNAPSHOT" --next-snapshot "$FIXTURE/snapshot-1" --second-snapshot "$FIXTURE/snapshot-2" \
    --coach-db "$HOME/Coach/data/coach.sqlite" --seed-manifest "$SEED" --proposal "$PROPOSAL" --out "$OUT"
  nice -n 15 python3 tools/migrate-deeptutor/verify-incremental.py \
    --snapshot "$SNAPSHOT" --next-snapshot "$FIXTURE/snapshot-1" --second-snapshot "$FIXTURE/snapshot-2" --seed "$SEED" --out "$OUT"
done
nice -n 15 python3 tools/migrate-deeptutor/verify-import.py --snapshot "$SNAPSHOT" --seed "$SEED" \
  --out "$HOME/Coach/corpus/ton-1095/writer-increment-2026-11-02-b/initial"
```

独立验证器直接读取源 SQLite/JSON、导入后的实体和有效事实，逐项核对题干/选项、消息正文/时间、作答、笔记、日志、已通过和复习队列；不以审计事件数代替可见状态对账。测试夹具第一轮只改一题，第二轮修订复习、作答、消息、笔记及日志元数据，另新增一条有内容的历史日志。原 242 个旧事件仍只存档。45 个未知类型、60 道五选项、2 条未解析回答均保留。课程、weights、原生事件/题目/消息、学习证据、复习和 pending 保持相同。每轮回退 SQLite 与自己的 before.sqlite 逐字节相同；每阶段 first/second 状态相同。

同一基线/三个输入在不同墙钟运行，基线、计划、各阶段规范化状态逐字节相同，实际各批审计时间不同。全库状态包含全部业务表与 schema，归一化仅屏蔽迁移审计时间。每阶段 SHA-256：

| 阶段 | 全库规范化状态 | 领域投影 |
|---|---|---|
| 初始（135 → 691 个事件） | `0aa45d2514d16a1b6d0bbc69b502e92e5ec1ab4512675c64561564785649472a` | `3e2740e689c0cd7344060317cb261432d9b49f09114f73277465d4eb4f657eca` |
| 增量 1（692 个事件） | `a3315fb4f90c57fb16ac6f08badcc46a1161b389ebe165287165980251ba5c80` | `d1525a29d97c93c44bada67c862fe07c11f01a0ab3b3f17c155f3941977007af` |
| 增量 2（698 个事件） | `c3adcbbcdc4946002636ed38e5ed46094af4b1d8053299fb2b5be10e397618e2` | `053d436889787d625b49adf67f3e6a82243e67316b0cd71ca594d7bc70402a84` |

确认草稿在私有输出的 `confirmation-details.md`：108 题、49 KP / 62 条前置、10 道简答源评分依据、2 条 B/c 原回答、60 道五选项/字母依赖解析，以及 9 个通过时间的近似说明，逐项附来源、建议与依据。107 道题有归属提议，1 道仍未知；没有独立 rubric 时只引用 explanation，不伪造分值，待本人核对。修订后的题目必须重新复核；本轮未询问本人，新增模型调用 0（卡内既有 2）。

仍未验证/完成：真实新快照的连续增量、无 ID 数组重排的人工 ID 对照、本人一次合并确认、新评分键的盲审、5 KP UI、blobs 备份及正式切换。该工具仍只写新建隔离副本；没有正式写库/激活入口，没有 DeepTutor 运行文件或 8790 访问，不结束整卡、不解锁 TON-1102。
