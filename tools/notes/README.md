# 本机按周复习笔记

仅 Python 3.10+ 标准库。读取 SQLite **拷贝**与可选本地课程材料，输出每周 Markdown、带原生 MathML 的离线可打印 HTML、首页和审计清单。没有服务请求、模型调用、下载、CDN 或 JavaScript。用浏览器打开 `index.html`，打印时选择 A4。

## 私人数据的运行方式

先做备份，之后只对拷贝运行。下列参数中的课程 ID、备份和材料路径由调用者提供；导出目录放在仓库外的私人目录。数据库、课程材料和真实导出都不能提交或上传。

```sh
mkdir -p "$HOME/Coach/state/notes-export-local"
sqlite3 -readonly "$HOME/Coach/data/coach.sqlite" ".backup '$HOME/Coach/state/notes-export-local/coach-copy.sqlite'"
python3 -m tools.notes \
  --database "$HOME/Coach/state/notes-export-local/coach-copy.sqlite" \
  --course COURSE_ID \
  --out "$HOME/Coach/state/notes-export-local" \
  --outline /path/to/private/candidate-outline.json \
  --material /path/to/private/revision-notes.md
```

`--outline`、`--material` 可省略，`--material` 可重复。默认 `--weeks 11 --audit-seed 1280`。程序拒绝直接读取生产 `Coach/data` 目录，也拒绝向 Git 仓库内导出；读取以只读事务进行。目录权限设为 0700，导出文件为 0600。没有修复、安装课程或更新学习记录的副作用。

每周包括要点、原始来源页码、公式、掌握度及历史错题的题干、选项、自己的答案、参考答案、已记录的讲解。公式只摘取明确数学标记与带等号条目，保留完整原文中的限定条件，不生成新讲解。没有记录的答案或错因明确标为待复核。原生 MathML 可在当前 Chrome、Safari、Firefox 离线显示；本工具不提供完整 TeX 排版引擎。未知命令保留原式，显示错误并记录在 `audit.json`；CLI 返回 2，不能据此声称公式验收通过。

## 数据口径与停止条件

- **周次**：归约 `outline_revised` 的知识点和增改/移动/退休操作。正整数 `week` 优先，否则使用标题内全部 `【Wn·…】`、`【Wn・…】`、`【Wn …】`、`【Wn】` 标签。跨周要点和错题分别出现在每个所属周，周计数不可相加作为唯一错题总数。未归周记录在审计清单列出，不擅自猜周。
- **补充提纲**：可提供与课程 ID 一致的 JSON，含 `knowledgePoints: [{id,title,week?,anchors?}]`。只补充尚未安装的 ID，始终显示“候选提纲尚未安装；掌握度未验证”。`week7: {reason: "…"}` 等字段可说明考试周或来源缺口，不能由导出工具猜测。
- **材料**：只按 Markdown **标题**里显式的 `Week n` / `Week n–m` 绑定内容，子标题继承，其他章节不自行归周。共享章节会保留多周归属；不以提及其他周的正文改变章节归属。
- **错题**：每次已提交且最新有效评分为 `0 <= score < max` 的作答都保留，后续重做正确不会抹掉旧错题。这是“做错过”的历史清单，与应用仅显示最新未订正选择题的 `weekMistakes` 口径不同。排除作废；人工改判合并 `revised` 分数；历史迁移只接纳有最终布尔对错且答案可解析、无澄清要求的记录，保留未复核标记。
- **答案**：使用出题时的 `letterMap`，不按选项数组重新编号；历史讲解涉及字母且旧映射不完整时隐藏歧义讲解。题库当前答案键与构造题参考答案带来源／复核标签，不把旧答案自动变成已复核答案。
- **掌握度**：读取与该课程最新事件序号一致的 v4 领域投影。版本不支持、缺失、落后均写“未验证”；投影自身标为需快照／含未知事件时展示缓存记录并明确“未验证”；历史通过另行标记。不会在 Python 中另造一套掌握度算法。
- **停止**：没有课程事件、输入结构错误、`legacy_source_revised` 历史修订（尚未接入领域修订归约器）、不安全输出位置时返回 2。只读导出不能绕过这些情况。

## 合成库验收与截图

仓库只有手写合成数据，测试不接触私人目录或生产服务。

```sh
python3 -m unittest tools.notes.test_exporter -v
python3 -m tools.notes.fixtures /path/outside/repo/synthetic.sqlite
python3 -m tools.notes --database /path/outside/repo/synthetic.sqlite \
  --course synthetic --out /path/outside/repo/synthetic-notes
```

合成库有重做、改判、作废、历史迁移、未解析答案、跨周知识点与反向选项映射。共 3 条唯一错题；W1/W2/W4 分别 2/1/1 条，其他周 0 条。测试还核对 HTML 转义、公式树、离线限制、投影状态和拷贝字节不变。

浏览器截图应优先分享合成示例。真实页面截图与真实导出一样，只能留在本机私人目录。

## 独立 SQL 对账

`audit.sql` 不调用 Python 归约函数，从账本独立求每周有效历史错题数。用 SQLite JSON1 与窗口函数；绑定 `:course`，返回 11 行聚合数字，不返回题干、答案或聊天。候选提纲未安装的周没有账本知识点，不因候选材料增加错题。

```python
import json, sqlite3
from contextlib import closing
from pathlib import Path

folder = Path("/path/outside/repo/private-export")
audit = json.loads((folder / "audit.json").read_text())
with closing(sqlite3.connect((folder / "coach-copy.sqlite").as_uri() + "?mode=ro", uri=True)) as db:
    counts = dict(db.execute(Path("tools/notes/audit.sql").read_text(), {"course": "COURSE_ID"}))
assert all(counts[int(w)] == count for w, count in audit["weekly_counts"].items())
print([(w, counts[w], audit["weekly_counts"][str(w)]) for w in audit["sample_weeks"]])
```

`audit.json` 包含随机种子和抽出的 3 周、每周计数、唯一错题数、未归周记录、公式警告、输入哈希与 22 个周文件的 SHA-256。可复核文件来源；不要上传这份含私人记录标识的审计文件。

## 接入应用

`load_snapshot(path, course)` 是只读 I/O 边界；`build_notes(snapshot, supplement, materials)`、`render_markdown(note)`、`render_html(markdown, title)` 是纯函数，不修改输入。`write_export(...)` 是唯一输出文件边界。未来按钮可复用这些函数，在应用侧完成拷贝与选择本地导出目录。当前改动不增加应用 UI，也不运行 Gradle。
