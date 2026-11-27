# 复习调度：FSRS-5 内核、每日上限与旧记录迁移

状态：已实现（TON-1787）。代码在 `shared/domain/src/commonMain/kotlin/com/coach/domain/fsrs/`。

## 1. 内核

`Fsrs5` 是纯函数式的 FSRS-5 记忆模型，按天调度，不依赖书、课程、存储或 UI：

- 状态：`FsrsState(dueDay, stability, difficulty, lastReviewDay, reps, lapses)`，`stability == null` 表示新卡。
- 公式：`retrievability`、`intervalDays`、`review`，以及 DSR 的各个分项（初始 S/D、短期 S、回忆 S、遗忘 S、难度收敛）。
- 默认权重是官方 FSRS-5 的 19 个参数；`desiredRetention = 0.9`；`maximumIntervalDays = 36_500`。
- 同一天再次作答走短期分支（`shortTermStability`），与参考实现一致。

**与参考实现对齐的方式**：`tools/fsrs-golden/gen_fsrs5_golden.py` 用钉住的 **py-fsrs 5.1.3**（FSRS-5 最后一条线；6.x 已改公式和权重数）生成 `shared/domain/src/jvmTest/resources/fsrs5-golden.json`——7 条复习序列、47 步，覆盖新卡、当日重复、逾期、遗忘、Easy 链到上限、全 Again 难度封顶。参考端配置为 `learning_steps=()`、`relearning_steps=()`、`enable_fuzzing=False`，即产品「整天粒度、不做当日阶梯」的设定。

`Fsrs5ReferenceParityTest` 逐步比对 S、D（相对误差 `1e-9`）和间隔（整数完全相同），并断言比对步数 ≥ 40；另有确定性测试。区间取整复刻 CPython `round()` 的 half-to-even，否则边界日会差一天。

复现：

```
./gradlew :shared:domain:jvmTest --tests '*Fsrs5ReferenceParityTest*'
```

## 2. 每日上限与顺延

`FsrsDailyQueue` 把内核的到期卡变成当天队列：

- 上限默认 **20**；考试日前 **21 天**（`daysToExam ∈ 0..21`）升到 **30**。
- 排序**严格**按 `考纲权重 × (1 − R(t))` 降序。权重取知识点在考纲里的占比，同一门课的多份材料（教材章节、讲义、真题拆解）共用一张队列，不假设只有一本书。
- 新卡没有可回忆的东西，`R(t)` 记 0，因此按权重排序。
- `waitingDays`（逾期天数）只在 urgency 完全相等时打破平局，**不设饥饿提升**：任何「等得久先上」的规则都会把队列变回积压清理器，考试前把高权重章节挤到后面。
- 超出上限的卡**不改写 FSRS 状态**、不隐藏，进入 `deferred`，界面读 `summary()` 得到「今日优先 N 题 · 另有 X 题顺延」。明天它们仍然到期且 R(t) 更低、urgency 更高，会自然排到前面。
- 考试窗口内还**提前拉取**：到期日晚于当天窗口上限的卡也算当天可复习（`FsrsDailyQueue` 的到期判定）。这类卡以真实的、偏高的 `R(t)` 进场，urgency 低，排在队尾，只占用更危险的卡用不完的额度。没有这一步，到期日落在考试之后的卡在整个窗口里都不会出现，「考前每张至少再见一次」就落空。

考试窗口的压缩参数在 `FsrsConfig.forExamWindow(daysToExam)`：`daysToExam ≤ 21` 时目标保留率升到 **0.94**，间隔上限收紧到 `max(1, ⌊daysToExam / 4⌋)` 天。它只收紧调用方给的配置，不会放宽更严的参数。这两个值来自 C-learning 方案（`proposal.md:230`、`sample-chapter/05-review-plan-fsrs.md:33`），是产品假设，不是拟合参数。

`FsrsDailyQueueTest` 覆盖上限切换、溢出计数、排序公式、新卡、多材料同队、空日与保留率统计，并钉住两条回归：更多赌注的卡必须压过等得更久的卡（9 个测试）。`FsrsExamWindowTest` 覆盖窗口外的等价、窗口内 R\*/上限收紧、只收紧不放宽、强卡也逃不出考试、以及窗口把排在考试之后的卡拉回（6 个测试）。

## 3. 负载模拟

`FsrsLoadSimulationTest`：19 章、每 4 天读一章（第 0…72 天）、考试日第 98 天，复习只跑到考前一夜（第 97 天），因此「考试当天」的 R(t) 是走进考场时的真实值，没有被当天早上的一次复习抬高。每天用 `FsrsConfig().forExamWindow(examDay − day)` 排程，答对/答错按 `R(t)` 伯努利抽样（新卡按 0.8），固定种子 `20261121`，每张卡 25 秒。

| 规模 | 卡片数 | 学习期峰值到期 | 学习期峰值顺延 | 考前峰值顺延 | 单日最长 | 考前每卡轮次 | 考前覆盖率 | 考试当天 R(t)（按权重） |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6–8 张/章（本卡定的量） | 132 | 16 | 0 | 102 | 12.5 分钟 | 4.64 | 100% | 0.9949（0.9951） |
| 12 张/章（红队最坏情况） | 228 | 26 | 6 | 198 | 12.5 分钟 | 2.76 | 100% | 0.9921（0.9923） |

结论（对应红队 P1-7）：

- 学习期本卡定的 6–8 张/章，20 张上限**从未触发**：峰值 16 张、最长 6.7 分钟，10–15 分钟的承诺有余量。12 张/章时第 9 周起触发，峰值 26 张、顺延 6 张、最长 8.3 分钟。
- 考前 21 天每天封顶 30 张 / 12.5 分钟。**顺延数不是积压数**：提前拉取让整本书每天都可复习，102 / 198 是「今天没轮到」的卡，不是「几周没见」的卡。按覆盖率衡量，每张卡在窗口内都至少见到一次（100%），平均 4.64 / 2.76 轮，考试当天早晨 R(t) 0.9949 / 0.9921。
- 所以上限约束的是**每天的时间**，不是积压数量。把顺延张数当 KPI 会得出「雪崩」的错误结论；能自证的标准是覆盖率与考试当天保留率，两者都达标。
- 顺延的确实是更安全的卡：考前展示卡平均 R(t) 0.9759 / 0.9652，顺延卡 0.9928 / 0.9864。
- 12 张/章已经超出本卡的 6–8 张，仍然 12.5 分钟/天、覆盖率 100%，代价是考前每卡只轮到 2.76 次，低于「约四轮」的目标。要更多轮次只能加时长或减题量，这是产品取舍，不是内核缺陷。

曲线输出：`shared/domain/build/reports/fsrs-load-sim-{lean,dense}.csv`（列：`day,due_before_cap,shown,deferred,new_shown,minutes,mean_retrievability,crammed`）、`-cards.csv`（逐卡权重、到期日、考前轮次）和 `-summary.txt`（上表的原始值）。

复现：

```
./gradlew :shared:domain:jvmTest --tests '*FsrsLoadSimulationTest*'
```

## 4. 旧记录迁移（1/3/7 → FSRS）

旧表 `book_reviews` 只留下 `due_day`、`streak`（连续答对次数）和 `book_review_history` 的逐次记录。`LegacyStreakMigration` 的映射：

| 旧记录 | 迁移路径 | 做法 |
| --- | --- | --- |
| 有逐次历史 | `HISTORY_REPLAY` | 按 `book_review_history` 的天数逐条重放：答对 = `GOOD`，答错/猜对 = `AGAIN`；D/S/遗忘次数都由 FSRS 自己算出来 |
| 只有 streak > 0 | `STREAK_ASSUMED` | 按旧的 1/3/7 间距（`interval(0)=interval(1)=1`、`interval(2)=3`、`interval(s≥3)=7` 天）倒推作答日，逐次重放 `GOOD` |
| streak = 0 | `NEW` | 视作没答过的新卡，`dueDay` 保留旧值 |

两条硬约束：

1. **不会比旧承诺更晚到期**：迁移后的 `dueDay = min(FSRS 算出的到期日, 旧 due_day)`。旧系统今天该复习的卡，迁移后今天仍然出现，不会被推到几天后。
2. **不伪造历史**：只有 streak 时，KDoc 和 `MigrationPath` 明确标注这是「假定全部答对」的近似；旧系统本来也只存这个信息。

`MigrationReport` 给出 `replayedHistory / assumedStreak / importedAsNew / pulledForward` 四个计数，便于迁移时对账（例如 `pulledForward` 是「FSRS 本想排得更晚、被旧到期日拉回今天」的卡数）。

`LegacyStreakMigrationTest` 覆盖 1/3/7 间距表、streak=0、streak 重放、历史重放中的遗忘、以及「绝不变晚」（5 个测试）。

接入时只需把 `book_reviews` 的每行读成 `LegacyCardRecord`（有历史就填 `answers`），写入新的 FSRS 存储；本卡不改 reader 和导入链路。

## 5. 测试与回滚

```
./gradlew :shared:domain:jvmTest --tests '*Fsrs*' --tests '*LegacyStreakMigration*'
```

全部为纯 `shared` 代码，未接 UI、存储或服务端；删除 `fsrs/` 包与 `tools/fsrs-golden/` 即可完全回滚。

## 6. 概念和批注共用同一每日上限（TON-1931）

题目、概念、读者自己的批注共用上面这条每日上限（平时 20，考前窗口 30），不另外加条数。概念和批注的记忆状态写在已有的 `book_fsrs_cards` 里：`mode` 为 `CONCEPT` 或 `NOTE`，`lesson` 为 `concept:<id>` 或 `note:<id>`。不新开表，也不改题目那几行的排序。没有概念或批注到期时，队列仍只含题目，摘要仍是「今日 N 题」。

隔离运行（设了 `COACH_URL`）可以用系统属性 `coach.todayOffsetDays` 把书库时钟往后拨整数天。`LocalDate.now(clock)` 和 `clock.millis()` 一起动，复习队列的「今天」跟着走。正式配对运行忽略这个属性，系统日期不用改。
