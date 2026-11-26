# 结构识别基线

在当前 Windows 导入路径上重跑书籍结构计数。工具不改阅读器。书的路径来自参数，不写进仓库。

报告、临时书库和 `--truth` 文件必须放在 git 检出之外。JSON 里只有数字、文件名和方法名，没有页文本、目录标题或完整路径。跑完默认删掉每本书的临时书库（里面有抽出的正文）。

## 指标

同一条生产路径：PDF 先 `stageBookImportTier0`（这时的 `reading_page` 是首次打开页），再 `indexBookTier1`。其它格式走 `stageBookImport`。tier-1 不改首次打开页。

- `nativeChapters` / `nativeBodyStart`：PDF 大纲、EPUB 导航或标题行数出来的章数和正文起始页。`--truth` 里同名文件的 `chapters` / `bodyStart` 会盖过自动结果，此时 `nativeMethod` 为 `given`，`autoChapters` 仍是自动数。
- `nativeChapterPages`：自动大纲给出的章起始页（最多 80 个），页眉对照用这份，不用被 `--truth` 盖过的章数。
- `softwareChapters`：导入后目录里 `CHAPTER` 节点数，对应界面「共 N 章」。
- `softwareTocNodes`：全部目录节点。章数正常但节点几百个时，看这个数。
- `softwareBodyStart`：`BookBoundaries.bodyStart` 的导入页。
- `firstOpenPage`：tier-1 之前保存的阅读页。`firstOpenRole` 只有结构明确标了角色才有值。
- `headerWrong`：从首个自动章页到最后一页均匀取最多 10 页。解析不到章、落到 `ch-all`、章起始页对不上，或章名像整段正文，算错。没有自动章页时为 null。
- `tocBodyMixed`：目录标题像整段正文（很长、带句号的中文，或又长又多句点的英文）。

自动章的判定顺序：显式 Chapter / 第N章；否则连续的「N 标题」；否则 Book/Part / 第N篇；否则大纲分层。最浅一层有 4 到 60 条就用它。只有 1 到 3 条、或超过 60 条时，改用下一层里 4 到 40 条的那一层。显式章名不因太长被丢掉。

## 运行

需要 JDK 21。书单文件用 UTF-8、无 BOM，一行一个路径，`#` 开头是注释。路径不要带空格时最稳；带空格的路径放进书单，不要放进 Gradle `--args`。

```powershell
tools\reader-v2\structure-audit\audit.ps1 `
  --library D:\agent\_trash\structure-audit-1875 `
  --out D:\agent\_trash\structure-audit-1875\report.json `
  --books-file D:\agent\_trash\structure-audit-1875\books.txt `
  --truth D:\agent\_trash\structure-audit-1875\truth.json
```

`--truth` 是 JSON 数组，或 `{ "books": [ ... ] }`。每项只有 `file`、`chapters`、`bodyStart`。`file` 按文件名（大小写不敏感）匹配。

`--self-check` 用一份编造的 Markdown 走导入，确认任务能编译、能写出报告。`--keep-library` 留下临时书库，用完要删，不要提交。

单元测试只覆盖分类器，不导入真实书：

```powershell
.\gradlew.bat :desktopApp:test --tests com.coach.desktop.books.StructureAuditTest
```
