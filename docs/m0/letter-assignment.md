# 发题时分配答案字母（TON-1076）

设计依据：`design/lessons-integration` 的 DESIGN §3.1、§3.2、§5.8 第 1 条。

题池使用 `Option(optionId, text)`，答案键引用 `correctOptionId`。指纹由 NFKC、空白归一、大小写归一后的题干与排序后的选项文本计算 SHA-256，选项身份、选项顺序和展示字母都不参与指纹。旧题有五个选项，导入时全部保留。

出题模型返回 `correct_text` 和 `distractors`。应用生成不含顺序含义的选项 ID。两种盲复核提示词均重新打乱选项，只带临时阅读编号；复核返回所选文本，应用按文本映射回选项 ID。临时编号与展示字母都不进入题池或复核结果。

`assignLetters(group, previousLastCorrectLetter, rng)` 为每题返回 `optionId → 字母`。每组正确字母互异，首题避开上一组末题；其余选项也随机分配。分组题数不得超过组内最少选项数，否则无法满足正确字母互异的约束。随机源可注入。

M0 服务入口：

- `LedgerStore.poseChoiceGroup` 为在线选题结果统一分配字母，在同一事务写入带 `groupId`、`letterMap`、题干、选项与指纹的 `question_posed`。同组重试返回已写入事件。
- `LedgerStore.offlineChoicePack` 接受离线选题结果，默认每组三道，调用相同分配函数；单独返回 `OfflineKey`，题卡事件不包含答案键。调用者通过 `LedgerActor` 串行执行。
- `submit_answer.posedSnapshot` 带原始 `groupId`、`letterMap`。已有出题事件时拒绝不同映射；回档补写时保留原快照映射。精确判分与反馈模板均使用该映射。

数据库 V002 转换旧题的字母键，迁移前备份，不改写历史事件；旧题选项或答案键不完整时置为 `UNREVIEWED`。DeepTutor dry-run 在每条题目记录的 `question` 字段提供可用的新结构，原始 `source_record` 保留迁移溯源，不作为新题结构使用。

复跑：

```sh
tools/dev/heavy ./gradlew :shared:domain:jvmTest :server:ai:test :server:store:test :server:app:test :tools:migrate-deeptutor:test --max-workers=2 --console=plain
tools/dev/heavy ./gradlew :server:ai:plantedReplay --max-workers=2 --console=plain
```

属性测试以 100 个随机种子各发 1000 道四选项题，检查每个字母占比在 25% ±5 个百分点内，同时检查每组三道及组间边界；另覆盖五选项题与混合选项数。合成六题回放使用实际 HTTP 桩，三份实际发出的提示词写到 `server/ai/build/reports/letter-prompts.txt`（构建输出不入库）。

`plantedReplay` 从 `COACH_CORPUS_DIR`（默认 `~/Coach/corpus/gradegen`）读取 TON-1041 的六题与原独立复核结论，经本地 HTTP 桩复放到新的文本/ID 协议，要求五道植入仍全部拦下、一道干净对照仍通过。这验证协议迁移后的结论保持一致；不调用订阅 CLI，也不构成一次新的模型复核。

真实迁移仍按 `tools/migrate-deeptutor/README.md` 对已有有效副本执行 `--dry-run --snapshot ... --out ...`，输出放私有语料目录。核对 `question.options` 的数量、`correctOptionId` 引用、指纹与 `UNREVIEWED` 状态，不把真实题目或映射文件加入仓库。
