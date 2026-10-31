# 分值进度、随口问与日记

M1-06 提供领域投影与服务端接口，供 M1-12/M1-13 的客户端页面使用。
今天页读取 `Projection.today(serverTime)` 或 `GET /v1/courses/{course}/today`。
进度问答读取同一个 `TodayProgress`，不调用模型、不改变待答状态。
`ExamCoverage` 汇总 `exam_weights_set.weights`，保留 `Projection.coverage` 的兼容字段名和 `Coverage` 类型别名；
`Projection.examCoverage` 是同一份值。未配置分值时明确显示无法计算，不退回按 KP 个数估算。
KP 个数比例留给路线页，分母按课程配置汇总（通常为 100）。

## 课程与进度问答

通过现有 `/v1/commands` 发送 `CommandBody`：

- `configure_course`：`payload.timeZone` 为 IANA 时区，默认 Europe/London。
- `set_exam_weights`：`payload.examWeights` 为 KP → 非负整数分值的对象，替换旧映射。

`msg.ask` 先用 `progressIntent` 的完整句法匹配分值覆盖、剩余分值、今日复习和当前 KP 状态；
只识别明确的问法，其他输入沿用模型问答。
确定性回答使用现有 `msg.start` / `msg.delta` / `msg.end`，首帧加 `source=projection`、`fromSeq`。
正文先持久化，可用 `msg.resume` 重取；不伪装成任何模型产出。
今天接口含分值正文、到期 KP、当前掌握和相同的 `fromSeq`。

## 随口问

WS 发送 `{"t":"quick.ask","courseId":"synthetic-course","text":"一个问题"}`。
服务端先发 `quick.thread`（threadId、courseId、day、kpId），再走常规消息帧。
同一课程每个逻辑日只建一条 QUICK 线程；创建时固定当前 KP，此后只读，重新打开和重启均复用。
日界线使用课程时区的当地 04:00，沿用含夏令时处理的 `logicalDay`。
QUICK 内的“继续”等输入也是普通问答，不推进导师流程；明确的进度问题仍直接读投影。

`GET /v1/courses/{course}/quick/{day}` 返回这一天的线程与持久化消息。
`file_quick_ask` 命令仅接受 `threadId`、`messageId`、`action=NOTE|MOVE`：
NOTE 保存到该线程固定 KP 的笔记；MOVE 将选定消息复制到该 KP 的线程并记 `quick_ask_filed` 来源，原消息保留作审计。
不要求 KP 当前正有导师状态，不产生题卡、作答或追问证据。
命令重复上传由原有命令幂等键去重。越课程、未完成消息、其他动作均拒绝。
QUICK 的公开掌握命令被拒绝，投影也排除误写在 QUICK 线程里的掌握事件。

## 日记

`GET /v1/courses/{course}/journal/{day}` 返回 `JournalDay`：统计、summary/fromSeq/summaryStale、manual/rev、legacyLogs。
`edit_journal` 命令携带 `day`、`expectedRev`、`content`。并发编辑返回 HTTP 409 / REV_MISMATCH，含当前修订与人工正文；不会覆盖赢家。
`POST /v1/courses/{course}/journal/{day}/summary` 基于捕获的统计调用订阅桥，记录 `ai_calls`，只在完整且可解析时写 `journal_summarized`。
无效或失败返回 502，旧摘要保留；摘要不会写回统计或人工区。生成期间有新事实时，新摘要也会立即标过时。
迟到复核若改变以前某日的统计，该日旧摘要同样过时。
`importLegacyJournal` 是供后续迁移使用的服务端方法：只读拷贝的调用方提供原条目 ID、逻辑日与正文，按原 ID 幂等存入独立旧日志区，不提供客户端任意导入命令。

Claude 的 HTTP 429 会明确提示并改用 Codex；两次物理请求分别记账。普通网络或格式失败不被当成限流。

## 复验

先 `date -u`、`uptime`，避开 DESIGN 的考前暂停时段，再运行：

```sh
tools/dev/heavy ./gradlew :shared:domain:jvmTest :server:store:test :server:core:test :server:app:test :server:app:progressReplay --offline --max-workers=2
```

`progressReplay` 只读 `COACH_REPLAY_DIR`（默认 `~/Coach/corpus/replay`）下的 `replay-input.jsonl` 和独立检查的 `progress-labels.jsonl`。
标签每行 `{"id":"与输入相同的标识","progressIntent":"COVERAGE"}`，其他类别为 REMAINING、REVIEW_TODAY、CURRENT_KP，非进度用空字符串。
缺输入或标签直接报“缺语料或独立标注，未运行”，不会把跳过记为通过。
输出仅含识别/误判/漏判数、实际零调用记录、投影回答数及本机耗时；可用 `COACH_PROGRESS_REPORT` 保存汇总。
真实文本、标签及源资料只放本机。历史 Coach 状态不存在，因此回放保留真实问法，配合成账本状态；不能据此声称历史数字被验证。
合成测试另核对 3 个账本时点的分值：零分前置通过仍 0/100；30 分 KP 两天闭卷正确后通过/稳固各 30；作废支撑后各回 0。
