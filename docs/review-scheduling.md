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
- 排序：`考纲权重 × (1 − R(t))` 降序。权重取知识点在考纲里的占比，同一门课的多份材料（教材章节、讲义、真题拆解）共用一张队列，不假设只有一本书。
- 新卡没有可回忆的东西，`R(t)` 记 0，因此按权重排序。
- 超出上限的卡**不改写 FSRS 状态**、不隐藏，进入 `deferred`，界面读 `summary()` 得到「今日优先 N 题 · 另有 X 题顺延」。明天它们仍然到期且 R(t) 更低、urgency 更高，会自然排到前面。
- 公平上界：连续顺延 ≥ 3 天的卡排在任何未顺延卡之前（同级内仍按 `权重 × (1 − R)`），防止低权重章节被考试权重永久饿死。

`FsrsDailyQueueTest` 覆盖上限切换、溢出计数、排序公式、新卡、饥饿提升、多材料同队、空日与保留率统计（8 个测试）。

## 3. 负载模拟

`FsrsLoadSimulationTest`：19 章、每 4 天读一章、11 周学习 + 21 天考前突击（考试日第 98 天），按 `R(t)` 伯努利模拟答对/答错（固定种子，可复现）。每张卡按 25 秒计。

| 规模 | 卡片数 | 峰值到期/天 | 峰值顺延 | 单日最长 | 展示卡平均保留率 |
| --- | --- | --- | --- | --- | --- |
| 6–8 张/章（本卡定的量） | 132 | 16 | 0 | 6.7 分钟 | 0.890 |
| 12 张/章（红队最坏情况） | 228 | 25 | 5 | 8.3 分钟 | 0.888 |

结论（对应红队 P1-7）：

- 本卡定的 6–8 张/章，20 张上限**从未触发**，日均 2–4 分钟、最忙一天 6.7 分钟，10–15 分钟的承诺有余量。
- 即使按 12 张/章，上限只在第 9–10 周触发，最多顺延 5 张，考前最后一天顺延归零；不存在雪崩。
- 学习期平均保留率 0.89，接近目标 0.9；被顺延的是 R(t) 更高的卡，损失有限。

曲线输出：`shared/domain/build/reports/fsrs-load-sim-lean.csv`、`fsrs-load-sim-dense.csv`（列：`day,due_before_cap,shown,deferred,new_shown,minutes,mean_retrievability`）。

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
