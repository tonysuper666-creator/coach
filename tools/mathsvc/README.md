# mathsvc · 公式服务（Coach M0）

TeX → SVG 的常驻小进程，桌面与安卓共用。Node 22 + `mathjax-full@3.2.2`（版本锁在 `package-lock.json`），
`fontCache: 'none'`，输出只含客户端 `MathSvgPainter` 会画的元素：`svg / g / path / rect / text`（含嵌套 `svg`）。

设计依据：`docs/adr/ADR-0005-渲染路线.md`、`docs/DESIGN.md` §4.5、§7.4。

## 跑起来

```bash
cd tools/mathsvc
npm ci
npm start            # 常驻，stdin/stdout JSON 行协议
npm test             # 用户可见质量测试（13 条：8 条渲染/协议 + 5 条合成语料）
npm run delims       # `$` 分词用例（36 条，语言无关的 delims/cases.json）
npm run corpus       # 重建 ~/Coach/corpus/math 真实语料（不进仓库）
npm run measure      # 起真进程跑全部语料，出 report.json / REPORT.md（--corpus 可改目录）
```

## 协议

一行一个 JSON，UTF-8；stdout 只写应答，日志走 stderr。

| 请求 | 应答 |
|---|---|
| `{"id":"1","tex":"\\frac{a}{b}","display":true}` | `{"id":"1","sha":"…","svg":"<svg …>","w":0.969,"h":0.697,"d":1.117}` |
| `{"id":"2","type":"ping"}` | `{"id":"2","type":"pong","pid":…,"uptime_ms":…,"rss_kb":…,"cache":…,"hits":…,"misses":…,"mathjax":"3.2.2"}` |
| `{"id":"3","type":"shutdown"}` | `{"id":"3","type":"bye","uptime_ms":…}` 然后退出 |

失败应答：

| `error.code` | 含义 | 客户端动作 |
|---|---|---|
| `tex` | TeX 写错（括号不闭合、`x^` 缺上标等） | 显示源码 chip，计入渲染失败数 |
| `unsupported-macro` | mathjax-full 3.2.2 不认识这个宏/环境，`macros` 给清单 | 同上；清单进「不支持宏」台账 |
| `unsupported-output` | 能渲染，但 SVG 出现子集外元素（如 `\cancel` 的 `<line>`） | 同上，`elements` 给元素名 |
| `protocol` | 请求本身不合法（没有 `tex`、JSON 坏） | 记客户端错误，重试无意义 |

### 尺寸与元素约定

- `w / h / d` 单位 **em**，来自 SVG 的 `viewBox`（MathJax 的 viewBox 是 1000 单位 = 1em）：
  `w = viewBox.width/1000`、`d = -viewBox.y/1000`、`h = viewBox.height/1000 - d`。
  `svg` 的 `width/height/style` 也改写成 em，客户端可以直接拿来排版。
- 输出是**单个 `<svg>` 根**（不带 `mjx-container` 外壳），`data-*`、`aria-*`、`role`、`focusable` 全部剥掉。
- 中文走 `<text>`：`<text transform="scale(1,-1)" font-size="884px" font-family="serif">斜</text>`。
  注意 `transform` 里的 `scale(1,-1)`——不翻转就倒着画；`font-size` 是 px，客户端按 em 比例换算后横向缩放估宽。
- 缓存键是 `sha256(tex) + display`，应答里的 `sha` 只是 `sha256(tex)`；`/health` 用 `ping` 帧代替（10 秒一次）。

## `$` 分词参考实现（`delims/`）

`delims/reference.ts` 是语言无关的参考实现，`delims/cases.json` 是它的用例（输入 → 期望片段），
Kotlin 侧（流式 Markdown / MathSvgPainter 的桥）按同一份 JSON 对齐即可。

规则：

1. **定界**：Pandoc 的 `$` 规则（开 `$` 后不能是空白；闭 `$` 前不能是空白、后面不能紧跟数字；正文里不允许出现未转义的 `$`，不跨行）；
   `$$…$$`、`\[…\]`、`\(…\)` 照旧；代码块、行内代码、`\$` 里的 `$` 永远不是定界符。
2. **bare-math 修复只对模型生成文本跑**（`source: 'model'`）。资料原文（`source: 'library'`）只做定界识别。
3. **修掉 SC 的 `<br>` 缺陷**：行内 HTML 标签是硬边界，修复不跨标签（`x=1<br>2` → `$x = 1$<br>2`，SC 会给出 `x=1<$br>2$`）。

比 Pandoc 多两条**保守硬拒**（都是为了「钱数永远是钱数」）：

- 行内公式正文里出现裸 HTML 标签 → 不是公式（OCR 行里两条货币被强行配对就是这种）；
- 中文只能出现在 `\text{…}` 里，裸中文 → 不是公式（`$7–8 年时，…$` 这种）。

另有两处修正是 SC 没有覆盖、但真实回答里会出现的写法：

- 多字母下标补花括号：`ε_arc` → `ε_{arc}`（否则 TeX 读成 ε·a·r·c）；
- 左式允许括号组：`ΔQ/(Q1+Q2) = 0.25/3.5 ≈ 0.07` 能整块进公式，而 `and … = …` 这种散文前缀不会。

## 语料与报告

真实语料不进仓库（仓库是公开的，见 DESIGN §12.1），默认在 `~/Coach/corpus/math/`，由 `npm run corpus` 生成，
来源逐条写在文件里；`npm run measure` 读同一个目录，把 `report.json` 与 `REPORT.md` 写回去。
路径都可用环境变量改：`COACH_CORPUS`（输出/读取目录）、`COACH_D1`（核查员产物，默认 `~/Coach/verifier-artifacts/d1`）、
`COACH_LIBRARY`、`COACH_DIGEST`、`COACH_STAGING`。

仓库里只有合成样例 `acceptance/corpus/math/`：

| 文件 | 内容 |
|---|---|
| `synthetic-formulas.json` | 20 条虚构公式 + 10 条虚构货币句子；`npm test` 用它，不需要本机语料 |
| `README.md` | 真实语料在哪、怎么生成 |

本机真实语料 `~/Coach/corpus/math/`（`npm run corpus` 生成，`npm run measure` 读它并写回报告）：

| 文件 | 内容 |
|---|---|
| `course-formulas.json` | 50 条课程公式（核查员 d1 的 `formulas50.json`，带原文件来源） |
| `tutor-answers.json` | 20 条真实导师回答整段 Markdown（DeepTutor `chat_history.db` 的两份迁移暂存拷贝，带 message id / session / sha256） |
| `currency-lines.json` | 课程库里所有含 `$数字` 的原句（579 行 / 1137 个 `$数字`），逐行给出「这个 `$` 有没有被当成定界符」 |
| `questions-with-math.json` | 10 道选项带公式记号或货币金额的题（题库 digest），裸记号抽成可渲染 TeX |
| `meta.json` | 计数、文件 sha256、来源路径、生成时间 |
| `report.json` / `REPORT.md` | `npm run measure` 的产物：成功率、元素分布、中英文形态、p50/p95、RSS、失败清单 |

`delims/cases.json` 里的句子与公式也全部是合成的（虚构数值），真实课程原句只在本机语料里。

## 已知限制

- `\cancel` / `\bcancel` / `\xcancel` 会画出 `<line>`，不在 Painter 子集内 → 走 `unsupported-output` 退成 chip（语料里 0 次）。
- `\color{…}` 这类正常宏会画出带 `fill` 的 path，属于子集内，但会覆盖 `currentColor` 主题色。
- 一行里同时有货币和公式时，判定只看 `$` 的配对；语料里 1137 个 `$数字` 的误判为 0，但新的 OCR 行仍可能造出「两条货币被配对」的形状，硬拒会把它挡回文本。
- MathJax 对「不认识的宏」默认画红字而不是报错；本服务用宏表前置检查把它变成显式错误，避免客户端把红字当成正常公式。
