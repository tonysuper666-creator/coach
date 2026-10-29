# acceptance/corpus/math

这里只放**合成样例**：`synthetic-formulas.json`（20 条虚构公式 + 10 条虚构货币句子）。
公式和句子都是编的，不来自课程库、题库或对话，可以公开。

真实语料不进仓库（DESIGN §12.1，仓库是公开的）：课程原文与教材页、真题全文、他的作答与对话、
DeepTutor 库副本一律放在本机：

```
~/Coach/corpus/math/
  course-formulas.json      # 50 条课程公式（来源：~/Coach/verifier-artifacts/d1/formulas50.json）
  tutor-answers.json        # 20 条真实导师回答整段 Markdown（chat_history.db 迁移暂存拷贝）
  currency-lines.json       # 课程库里所有含 `$数字` 的原句（逐行判「这个 $ 有没有被当定界符」）
  questions-with-math.json  # 10 道选项带记号或货币金额的题
  meta.json                 # 计数、文件 sha256、来源路径
  report.json / REPORT.md   # measure 的实测产物
```

生成与实测：

```bash
cd tools/mathsvc
npm run corpus     # 写 ~/Coach/corpus/math/（COACH_CORPUS 可改，COACH_D1 / COACH_LIBRARY / COACH_DIGEST / COACH_STAGING 改来源）
npm run measure    # 读 ~/Coach/corpus/math/ 出 report.json 与 REPORT.md（--corpus <目录> 可改）
npm test           # 仓库侧测试：渲染与分词都用本目录的合成样例，不需要本机语料
```

生成脚本只读来源：`~/Coach/verifier-artifacts/d1`（核查员产物）、DeepTutor 库、`~/Coach/migrate-staging/<时间戳>/` 里的库拷贝。
