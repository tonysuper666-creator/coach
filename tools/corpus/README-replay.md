# 本机复盘语料

`prepare-replay.py` 只准备 DESIGN §5.8 / §12 的输入，实际 Coach 回放及断言验收由后续任务完成。Python 3.11+，仅标准库；不调用订阅 CLI、不运行 Gradle。

首次导出或空闲时更新：

```sh
python3 tools/corpus/prepare-replay.py --snapshot
```

默认从 `~/DeepTutor` 复制到 `~/Coach/corpus/replay/source/<UTC时间>-<随机后缀>/`。复用迁移器 `Snapshot.kt` 的做法：桥日志最后活动与 mtime 均至少十分钟前；复制 db 与存在的 WAL，不复制 SHM；前后核对整个 data 树的 size/mtime，变化即丢弃；只对副本打开 SQLite，运行完整性检查，再在副本上 VACUUM INTO，将副本 WAL 合并为 `chat.snapshot.db`。复制可能触发源文件访问时间更新，但不会写源文件内容或创建源 SQLite sidecar。复制目录为 0700，输出文件为 0600；库拷贝与证据均不入 git。

同一快照重新生成（JSONL 和 summary 字节一致）：

```sh
python3 tools/corpus/prepare-replay.py
```

可用 `COACH_REPLAY_DIR` / `--output` 和 `COACH_DEEPTUTOR_DIR` / `--deeptutor` 覆盖路径；真实输出拒绝存放在仓库和 DeepTutor 内。`--since` 默认 `2026-10-23T00:00:00+01:00`（当天伦敦零时，包含后续夏令时切换）；覆盖值必须带时区。缺少本机库时退出 2，明确报「缺语料，未运行」，不会自动用合成样例代替。源库变动或桥活跃时在下一个空闲窗口重试，不主动停服务。

输出均在本机目录，共用旁边的 `SOURCE.md`（来源、拷贝时间、生成命令）：

- `sessions.jsonl`：每行一个入选会话，包含 session 行及**完整原始消息**，包括窗口之前的上下文、结构化题卡/工具事件、消息主键、原始时间和软删除标记。会话中有截止时间之后属于目标课程的用户发言才入选。
- `replay-input.jsonl`：每条目标课程的窗口内用户发言一行，含稳定源 ID、UTC 时间、原始用户行，以及前后相邻用户轮次之间的全部助手行；没有前/后回复时对应数组为空。按会话创建时间/ID，再按消息时间/ID 排序；保留 `parent_message_id` 供下游处理分支。
- `summary.json`：会话数、消息数、用户发言数、意图分布、unknown 占比、源 hash、是否合成。
- `source/…/copy-stats.json` 与 `SOURCE.md`：复制时间、源 data 前后 stat 和拷贝 hash；供验收核对。

课程归属用每条用户消息的 `metadata_json.request_snapshot` 中 `knowledgeBases` / `courseId`，没有非空课程线索时退回会话 `preferences_json` 的 `knowledge_bases` / `course_id`；courses.json 的课程名用于映射内部 course ID。显式属于其他课程的发言不会混入回放。不会根据聊天内容猜课程，未显式绑定的会话不算 ECNM10119。会话上下文可能含其他课程消息，只供回放上下文，不自动当目标输入。

意图规则（优先级从上到下，规则版本随每条输出记录）：结构化 `masteryAnswer` → `answer`；单独选项字母/数字 → `answer`；明确的进度问法 → `progress`；拒绝自述/只做选择题 → `mcq_only`；多题要求 → `request_batch`；完整短指令「出题/考我…」→ `request_question`；完整短指令「继续/快点/下一步/下一题…」→ `continue`；问号或明确疑问开头 → `question`；其余一律 `unknown`。这些是可审计的标注，不宣称理解所有自然语言；unknown 原样保留供总验处理。

`assertion_candidates` 是按意图关联的八类回放检查 ID；**不是已通过结果**。`requires_coach_state=true` 表明需下游从 Coach 事件/投影获得已讲概念、题卡、letterMap、窗口等，不能凭 DeepTutor 助手文字猜测预期状态。涉及 1000 题的分布、生成失败回退、泄题等仍需总验自己的属性测试/故障注入。

无私人语料环境和 CI 显式使用四段合成会话：

```sh
python3 tools/corpus/prepare-replay.py --synthetic tools/corpus/fixtures/replay-synthetic.jsonl --output ~/Coach/corpus/replay-synthetic
tools/dev/heavy python3 -m unittest discover -s tools/corpus -p 'test_prepare_replay.py' -v
```

样例使用虚构花卉/图形，覆盖默认三题与字母、继续不重讲、缺卡回退、协议污染、选择题可通过、先讲后考/反馈字母、进度投影；合成输出带 `synthetic=true`。测试验证标注和前后文、课程/时间筛选、可复现导出，以及临时 WAL 库的数据保全和源目录不被 SQLite 打开。

验收者在本机对 `source/…/chat.snapshot.db` 独立查询 `messages`，随机抽 5 条 `replay-input.jsonl`，逐字比较 user、同 session 内相邻助手回复、原始时间与 ID；不在评论中粘贴真实会话。源码自查命令见 DESIGN §12.1。

第二次导出可在 DeepTutor 无活跃会话、机器负载低的空闲窗口再次 `--snapshot`，不必等考后；先看 `date` 和 `uptime`，2026-11-02 00:00–15:00 GMT 暂停采样。保留第一次输出和第二次输出的 summary 分别报告；同一快照的重复导出仅用于可复现验证，不算第二次采样。

## Coach 副本与总验回放

Coach 生产库先从只读连接执行 VACUUM INTO，之后仅打开副本。下面的命令不会启动、重启或写入生产服务。目标文件必须不存在：

~~~sh
python3 - <<'PY'
from pathlib import Path
from datetime import datetime, timezone
import os, sqlite3
os.umask(0o077)
root = Path.home() / 'Coach/corpus/replay/source'
target = root / datetime.now(timezone.utc).strftime('coach-%Y%m%dT%H%M%SZ')
target.mkdir(parents=True, mode=0o700)
source = Path.home() / 'Coach/data/coach.sqlite'
db = sqlite3.connect(source.as_uri() + '?mode=ro', uri=True)
db.execute('VACUUM INTO ?', (str(target / 'coach.snapshot.sqlite'),))
db.close()
(target / 'SOURCE.md').write_text('Source: Coach production; read-only VACUUM INTO; UTC ' +
    datetime.now(timezone.utc).isoformat() + '\n')
print(target.name)
PY
python3 tools/corpus/prepare-replay.py --coach-copy ~/Coach/corpus/replay/source/coach-<UTC>/coach.snapshot.sqlite --since 2026-10-31T13:59:00Z
~~~

Coach 单独输出 coach-sessions / coach-replay-input / coach-events / coach-summary 和 coach-SOURCE，不覆盖 DeepTutor 的文件。复用相同意图标注及相邻助手上下文算法，保留原始毫秒时间与消息行。若库里没存用户文本，不从 kp_opened、followup_asked 等事件推测他说了什么；0 条就是未覆盖。

显式总验入口（不属于默认 test/check），所有输出留在私有 replay/runs 下：

~~~sh
python3 tools/corpus/prepare-replay.py --synthetic tools/corpus/fixtures/replay-acceptance.jsonl --output ~/Coach/corpus/replay/synthetic
COACH_ACCEPTANCE_INPUT="$HOME/Coach/corpus/replay/synthetic/replay-input.jsonl" COACH_REPLAY_SYNTHETIC=1 COACH_REPLAY_LIVE=1 tools/dev/heavy ./gradlew :server:app:acceptanceReplay --max-workers=2
COACH_ACCEPTANCE_INPUT="$HOME/Coach/corpus/replay/replay-input.jsonl" COACH_REPLAY_LIVE=1 tools/dev/heavy ./gradlew :server:app:acceptanceReplay --max-workers=2
~~~

先确认 date 不在停跑窗口、uptime 与 8791 无在途请求。每轮顺序输入原始用户发言；真实 CLI 只走 8791，从不连接 8790。历史单选字母顺序累积成当前组答案，不把原来的字母改成正确答案；原题与 Coach 题池不同，因此不把历史正确率当作回放判分准确率。其他发言原样走 ChatService。无 CLI 时可显式使用 COACH_REPLAY_STRUCTURAL_ONLY=1，报告 live=false、replayed=0，不冒充 live 通过。

report.json 分开列出 source_utterance_assertions（原始用户发言触发）和 structural（合成状态与故障注入），逐项报 samples/failures，0 为未覆盖。任意失败使命令非零退出，仍先写完整报告。1002 道题经过真实持久化分配路径，检查所有滑动 1000 题窗口和连续 3 题（含跨组）；不是仅比较组内与相邻一题。故障原因注入账本层；真实模型空/纯文字/错格式/超时的完整解析路径另由 PoolWorkerTest 覆盖。真实回放的开场状态是隔离新线程和一个本机已复核 KP 题池，不声称重建了 DeepTutor 的历史 Coach 状态。四种 KP 标签的选择题通过探针只验证状态机不以类型阻塞通过，不代替各类型题池生成质量验收。

已有黄金用例的复跑入口：

~~~sh
tools/dev/heavy ./gradlew :shared:domain:jvmTest :server:core:test :server:ai:test :server:store:test :server:app:test --max-workers=2
tools/dev/heavy ./gradlew :shared:ui:jvmTest --tests '*RenderingQualityTest' --tests '*MarkdownStructureTest' --max-workers=2
~~~

ProjectionTest 已覆盖 DST/04:00、离线包不劫持继续、作废撤销、晚到复核；MasteryInvariantPropertyTest 覆盖提示/assisted/复习不撤销；OfflineRestoreTest 与 TwoRestoresOfflineTest 覆盖备份早于下载、在线已确认作答恢复和回档纪元；NumericValueTest 与 VerbatimEvidenceTest 覆盖中文短答。ReplayGoldenRulesTest 补 108 道 LEGACY 复核后仍不进窗口与考前压缩间隔边界。RenderingQualityTest 从本机已有 render/currency.json 读取货币原句，没有语料时报告 skipped，不能算货币实测通过。PR #25 未合并时，构答链路需另在其 head 上验证，不能将 main 的结果当作该 PR 的结果。
