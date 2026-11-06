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

`load_snapshot(path, course)` 是 CLI 的只读 I/O 边界；`build_notes(snapshot, supplement, materials)`、`build_point_note(snapshot, point_id)`、`render_markdown(note)`、`render_html(markdown, title)` 是纯函数，不修改输入。

桌面课程页的「导出复习提纲」可以选择周次或知识点、本机文件夹，以及 Markdown / Markdown + PDF。每次点击打开对话框时冻结当前已同步账本，保存到所选目录中新建的 `Coach-复习-日期-随机标识` 文件夹；成功后可以打开文件夹。输出不覆盖旧文件，目录 0700、文件 0600；拒绝 Git 仓库目录。取消选择不写文件，失败清理未完成文件夹。导出期间仍可同步，但不会修改这份快照。

桌面通过 `notes.desktop` 的 JSON 管道调用**同一份** Python 核心，安装包包含代码 zip，无须检出仓库或访问服务端 SQLite。需要本机 Python 3.10+；可以通过 `COACH_PYTHON` 指定解释器。账本通过进程管道传递，不生成临时私人快照，也不调用模型或上传。桌面仅复用已经同步到账本的答案与讲解；未下发到客户端的参考答案明确标为不可用，不另行抓取隐藏答案。

桌面错题清单使用应用 `pendingChoiceMistakes` 的待练选择题快照；提取器仍归约完整账本，然后过滤这份选择，并核对结果 ID 集合完全一致，否则停止。CLI 默认仍导出历史错误，二者都走 `exporter.py`，在文件中说明各自口径。掌握度使用冻结的应用投影标签。要点、来源和公式来自账本中的知识点标题、证据锚点与错题讲解，不把可能包含整章教材的备课包原文附入提纲，也不调用模型另写摘要。空周写「本周无错题」。

PDF 复用 `FormulaExport.kt` / `MathSvgPainter` 绘制的公式，优先复用当前窗口 SVG 缓存，缺失时请求已配置 Mac 的公式服务。它使用 A4、144 dpi 无损位图页面，中文及分式可离线打印；PDF 正文不能搜索或复制，随附 Markdown 保留可编辑文本。公式服务拒绝、不可用或公式过宽时，该处明确标注「公式未能清晰绘制，保留原始 TeX」，完成提示报告数量；这与应用保留源码的退路一致，不能当作公式全部渲染成功。公式位图超出安全尺寸或超过 100 页时停止；也可取消 PDF 勾选后导出 Markdown。

PDF 可嵌入账本 Markdown 中自带的 `data:image/png;base64,…` / JPEG 图片；解码全程在内存，限制编码长度、宽高和像素数。不会访问图片网址或文件路径。外部或损坏的图片在 PDF 中明确标注未嵌入，完成提示报告数量，原引用仍完整保存在 Markdown。

## 桌面和模拟器验证

```sh
python3 -m unittest tools.notes.test_exporter -v
tools/dev/heavy ./gradlew :desktopApp:test :shared:domain:jvmTest :shared:ui:jvmTest \
  :server:app:installDist :desktopApp:createDistributable --console=plain
tools/dev/heavy ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest \
  -PcoachApplicationId=com.coach.android.ton1294 -Pcoach.port=18894 --console=plain
```

`NotesExportTest` 实际运行安装包内的 Python，比较应用重做正确／作废过滤、五条错题、知识点选择、冻结字母映射，验证私有文件权限、拒绝仓库和 PDF 分页／xref。Python 测试将合成桌面导出逐行对照 `desktop-example.md`，并覆盖空周、知识点、输入不变与口径不一致时停止。

窗口自动验收钩子使用 `COACH_NOTES_VERIFY_DIR`、`COACH_NOTES_VERIFY_WEEK`、`COACH_NOTES_VERIFY_CAPTURE`：自动打开真实对话框、捕获其 Compose 表面，并调用与导出按钮相同的代码。必须同时设置独立 `COACH_URL`、`COACH_TOKEN_FILE`、`COACH_CLIENT_DIR`；开发守卫拒绝生产端口。输出目录必须已存在且在仓库外。可用 `JAVA_TOOL_OPTIONS=-Dcoach.autoExitSeconds=35` 限定窗口生命周期；包含大量公式时应延长并检查实际导出是否完成。

模拟器只在 `tools/dev/heavy --emulator` 内使用 `coach_1128_c70_r`。`NotesReviewSmoke` 接收独立端口、一次性配对码、周次与 `expectedCount`；host 把同一数据导出的 Markdown 放入测试包 `cache/desktop-notes.md`。测试配对、同步、对照题干／选项／讲解与应用题卡，推进**副本**的学习游标后从今天页点击「再练这一周错题」，核对可见清单并在 cache 下保存截图。它不请求生成、也不提交新答案。真实数据的截图和日志只保存在本机私人目录，不上传。错题不足五条时必须如实记录，不能补造真实作答或把合成对照说成真实五题验收。

本卡负责人已批准“两条真实逐题对照 + 五条合成”的证据口径。`fixtures/edge-cases.json` 全部是合成内容：S1 公式、S2 内嵌图片、S3 三十段超长题干、S4 空选项、S5 普通单选，共五条待练题；另加 S6 多选，验证其与现有周错题页同步排除（应用该页仅收录 `SINGLE_CHOICE`，不冒充已支持多选复习）。`NotesExportTest.syntheticEdgesKeepFivePendingAndExcludeMultipleChoice` 实际调用打包的提取器，逐条核对原文和讲解，并检查图片解码／拒绝外部资源；不向生产账本插入这些样例。

设备测试还逐题核对自己的答案，保存 `cache/notes-review/question-N.png` 与不含题目内容的 `agreement.txt`。启动模拟器时将 `ANDROID_SDK_ROOT` 指向实际包含 system-images 的 SDK，不能仅凭 Gradle 的 SDK 路径猜测 emulator 路径。测试命令（在已取得 heavy 模拟器锁的前台会话内）：

```sh
# 仅在自己的隔离包和模拟器内；先确认文件确实进入应用 cache。
adb -s emulator-5574 push "$PRIVATE_DESKTOP_MARKDOWN" /data/local/tmp/ton1294-desktop-notes.md
adb -s emulator-5574 shell run-as com.coach.android.ton1294 mkdir -p cache
adb -s emulator-5574 shell run-as com.coach.android.ton1294 cp \
  /data/local/tmp/ton1294-desktop-notes.md cache/desktop-notes.md
adb -s emulator-5574 shell rm -f /data/local/tmp/ton1294-desktop-notes.md
adb -s emulator-5574 exec-out run-as com.coach.android.ton1294 cat cache/desktop-notes.md > "$PRIVATE_CHECK_COPY"
cmp "$PRIVATE_DESKTOP_MARKDOWN" "$PRIVATE_CHECK_COPY"
adb -s emulator-5574 shell am instrument -w \
  -e coachPort 18894 -e pairingCode "$PAIRING_CODE" -e week 1 -e expectedCount 2 \
  -e class com.coach.android.NotesReviewSmoke \
  com.coach.android.ton1294.test/androidx.test.runner.AndroidJUnitRunner
```

真实副本使用 18894、合成库使用 18896，各用自己的数据目录、令牌、客户端缓存；不连接生产端口。读取两条真实题的原文截图仅在本机进行；对外证据只分享合成截图、合成 PDF 和无题目内容的逐题通过结果。
