Windows 桌面外观成熟度打磨（TON-1654）。V7「案头 · 定稿」和 `CoachV7.kt` 仍是规范来源，这里只记从成熟产品借来的做法、逐条检查的结果和修复去向。TON-1650 管「颜色字号是否走 token」，这里管「每个状态有没有被设计过、细节是否一致、像不像一个 Windows 应用」。

- 截图基线：main `941b08a2`，`tools/dev/heavy ./gradlew :desktopApp:test --tests 'com.coach.desktop.DesktopV7ScreenshotTest'`（winbox，exit 0，gradle 143.5 s），31 个场景 × 1920×1080 / 2560×1440 × 浅/深。
- 截图位置（不进 Git）：winbox `D:\agent\design\polish-shots\941b08a2\<尺寸>\<light|dark>\<场景>.png`；下文「截图」列只写 `<主题>/<场景>`，默认 1920×1080。
- 参照截图（别人产品，不进 Git、不打包）：winbox `D:\agent\design\polish-refs\`。
- 盲评素材：winbox `D:\agent\design\polish-blind\round0\`（匿名 `img-NN.png`，Coach 12 张混 Readest 5 张）；对照表在 `_key\round0.csv`，评审者不要打开。

## 1. 参照

| 参照 | 链接 | 借的细节 | 在 Coach 哪里用 |
| --- | --- | --- | --- |
| Readwise Reader | https://docs.readwise.io/reader/docs/faqs/navigation | 键盘优先：每个按钮的快捷键写在悬停提示里，`?` 打开快捷键表 | 阅读页、做题页的按钮悬停提示带快捷键（第 3 节 K5、M3） |
| Readest | https://github.com/readest/readest （截图 `polish-refs\readest\`） | 全键盘翻页；脚注用就地浮层而不是跳页；深色是暖灰纸而不是纯黑 | 本机阅读补方向键翻页（K3）；「出处」保持就地打开 |
| Zotero 阅读器 | https://www.zotero.org/support/pdf_reader | 批注栏和正文分栏，出处写「第 N 页」这种人能读的位置，不露内部编号 | 讲解卡「出处」改成页码和段落（A11） |
| iA Writer | https://ia.net/writer/support/editor/focus-mode 、https://ia.net/writer/support/basics/settings | 专心模式只淡化周围；正文行长控制在 64–80 字符 | 专心读与正文栏宽（T5，已对） |
| Kindle for PC（2026 新版） | https://blog.the-ebook-reader.com/2026/06/04/new-kindle-reading-app-for-windows-now-available-to-download/ | 阅读视图只留正文，字体字号主题收在一个 `Aa` 面板 | 顶栏 `Aa` 已有；阅读页说明性小字收进提示（A12） |
| 微信读书 PC | https://sj.qq.com/appdetail/1104692919 | 字号、页边距、护眼底色三项就够，不堆设置 | 不新增设置项；只核对现有 `Aa` |
| Apple Books（Mac） | https://support.apple.com/guide/books/welcome/mac | 书架只显示书名和进度，空书架只给一个动作 | 书架空状态（E1，已对） |
| Things 3 | https://culturedcode.com/things/ | 同一屏只有一个主按钮，其余是文字按钮；悬停有轻底色 | 今天页两个黑底主按钮降为一主一次（A13）；按钮悬停（M1） |
| Bear | https://bear.app | 字重分三级、灰分三级，层级靠字号和颜色而不是卡片 | 书架三张模式卡去掉阴影改分栏（A1） |
| Refactoring UI | https://www.refactoringui.com/ | 空状态给下一步；错误写原因和下一步；不用默认灰 | 讲解失败带「重试」、导入错误写下一步（E3） |
| Carbon 空状态 | https://carbondesignsystem.com/patterns/empty-states-pattern/ | 空状态 = 一句说明 + 一个动作，不写「暂无数据」 | `LocalExamUi.kt:129` 的「暂无」改人话（C2） |
| clreq 中文排版需求 | https://www.w3.org/TR/clreq/ | 行首行尾禁则、标点挤压、中西文间距 | 正文与讲解卡（T1–T3） |
| Butterick 行长 | https://practicaltypography.com/line-length.html | 一行 45–90 字符 | 讲解卡、对话框正文栏宽（T5） |
| Fluent 2 标题栏 | https://learn.microsoft.com/en-us/windows/apps/design/basics/titlebar-design （`polish-refs\fluent\titlebar-overview.png`） | 标题栏左上角放应用图标，标题写「文档 · 应用名」 | 窗口图标与标题（W1、W2） |
| Win11 贴靠 | https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/ui/apply-snap-layout-menu | 1/2、1/3、1/4 屏都要能用 | 最小窗口尺寸与窄窗布局（W3） |
| Fluent 提示 | https://learn.microsoft.com/en-us/windows/apps/design/controls/tooltips （`tool-tip.png`） | 悬停约半秒出提示，写名称和快捷键 | 图标按钮与工具栏（M3） |
| Fluent 键盘加速键 | https://learn.microsoft.com/en-us/windows/apps/design/input/keyboard-accelerators | 快捷键要在提示或菜单里看得见 | K5 |
| Fluent 焦点 | https://learn.microsoft.com/en-us/windows/apps/design/input/guidelines-for-visualfocus | 焦点环只在键盘操作时出现，鼠标点击不留环 | `v7FocusRing`（K2） |
| Fluent 滚动条 | https://learn.microsoft.com/en-us/windows/apps/design/controls/scroll-controls （`scrollbar-*.png`） | 可滚动区域要有细滚动条，悬停变粗 | 长页面滚动条（W5） |
| 让应用在 Win11 上好用 | https://learn.microsoft.com/windows/apps/get-started/make-apps-great-for-windows | 跟随系统明暗、各缩放比例清晰、图标齐全 | W4、W6、W7 |

## 2. 名字与 logo（李政 11-16 交给本卡定）

### 界面里的名字

结论：**界面内的品牌位保留短名「Coach」**，小号无衬线、`uiStrong`，不换成全名，也不加图形。全名 LearningCoach 只出现在安装包、exe、开始菜单、窗口标题栏和关于页（TON-1656）。

理由：
1. V7 顶栏是一行 48dp 的小号标签，左边字标后面紧跟「目录」「返回书架」，中间是书名。全名有 13 个字符，宽度约为「Coach」的 2.5 倍，阅读页上会把面包屑往右挤，窄窗（1/2 贴靠）时和书名相撞。
2. Windows 的标题栏就在顶栏正上方，已经写着「LearningCoach」和图标；窗口内再写一遍全名是重复。成熟的 Windows 应用（Word、设置、Edge）窗口内都不重复全名。
3. 「Coach」本来就是 V7 设计包顶栏里的写法，`Desk.kt` 和 `WindowsTextbookHost.kt` 已经是它，保持不动最省、也不和设计包分叉。
4. 不加 logo 图形：顶栏是「定稿」的页眉，只有字；图形留给标题栏左上角、任务栏和开始菜单。

TON-1656 第 3 条按此收尾：顶栏字标不改字，只确认窗口标题栏是全名。

### logo 与应用图标

三个方向（源文件 `docs/design/logo/`，全部自己画，不用字体字形、不用别人的图形）：

| 方向 | 文件 | 来由 | 结论 |
| --- | --- | --- | --- |
| A「页边 · 定稿」 | `a-margin-seal.svg`，小尺寸版 `a-margin-seal-small.svg` | V7 的三样东西：纸面、左侧松绿页边线、右下角一方朱印（「定稿」盖章）。读书和批改都在这一页上 | **选定** |
| B「朱印」 | `b-seal-book.svg` | 整块朱色印面，留白刻一本摊开的书 | 落选：白色摊开书在暖色方块上，和 Apple Books（橙底白书）撞脸；红方块也接近 Zotero |
| C「选项即课本」 | `c-choice-page.svg` | 纸上三行选项，第二行点朱色，对应「选择题当课本」 | 落选：单选列表在 16px 下像待办或表单类应用（Microsoft To Do、Google Forms） |

选 A 的理由：只用 V7 已有的三种颜色和一个纸片形状，没有渐变、发光、3D；16px 下去掉正文线后仍剩「浅纸 + 绿竖线 + 红方块」三个块，在浅色和深色任务栏底上都能认出（预览图 `logo-sheet.png` 附在 TON-1654 评论里）。纸片外加一圈 `#857D71`/`#B9B0A1` 细边，否则在浅色任务栏 `#F3F3F3` 上纸色会化开。

对比过的学习、阅读类图标：Apple Books、Kindle、微信读书、Readwise Reader、Zotero、Readest、Anki、Goodnotes、Notion、Obsidian、iA Writer。最接近的是 iA Writer（浅底加一条竖线，但它的是蓝色光标、居中），A 的竖线是贴左的松绿页边、另有朱印，区分得开。

交付文件：
- `desktopApp/icons/LearningCoach.ico`：16、20、24（用小尺寸版，按像素对齐）和 32、40、48、64、256（用完整版），PNG 内嵌。
- `desktopApp/src/main/resources/icons/learningcoach-{16,24,32,48,256}.png`：给 `Window(icon=)` 用。
- 接线（`build.gradle.kts` 的 `windows { iconFile }`、`Window(icon=)`）和 winbox 任务栏 16/32px 浅深色实拍：这两个文件 TON-1656 正在改，交给 TON-1656 一起做，不在本卡里改，避免同一文件两人改。

## 3. 清单

状态：**已对** = 截图或代码里核过没问题；**待修** = 有问题，写了去向；**未验证** = 本轮没在真窗口里核到。

### AI 味

| # | 项 | 状态 | 截图 / 位置 | 说明与去向 |
| --- | --- | --- | --- | --- |
| A1 | 千篇一律的圆角卡片 + 阴影 | 待修 | `light/bookshelf`、`light/quiz`、`light/exam-attempt` | 书架三张模式卡、题目选项、考试选项全是同一种白卡加阴影。选项改成纸面上的行（选中用朱色描边或底色），模式卡去阴影。去向：选项归 TON-1650 第 13 刀之后的做题泳道；书架卡见卡 P3 |
| A2 | 间距处处一样 | 待修 | `light/course` | 课程页按钮和「本地练习与记录」标题之间只有约 12dp，比标题和正文的距离还小，层级读反。去向：TON-1650 偏差清单补一条（今天/课程泳道） |
| A3 | 只有一种字重和一种灰 | 已对 | `light/reader`、`dark/quiz` | 宋体标题、正文、`sub`、`faint` 三级灰已分开 |
| A4 | 图标风格混用 / emoji | 已对 | 侧栏 | 只有侧栏三枚线性图标，风格一致，无 emoji |
| A5 | 渐变、发光 | 已对 | 全部场景 | 没有 |
| A6 | 讨好口吻、感叹号 | 已对 | 代码 `rg '！'`：界面文案 0 处（只出现在断句正则里） | 没有「欢迎」「太棒了」 |
| A7 | 空状态「暂无数据」 | 待修 | `LocalExamUi.kt:129` | 「部分章节暂无可用题」改成「有几节还没有题，这份练习只出了已有的。」去向：卡 P4 |
| A8 | 长文居中 | 已对 | `TextAlign.Center` 仅 1 处，非正文 | |
| A9 | Material 默认残留 | 待修 | `dark/notes-export`（绿色实心勾选框、▾ 下拉）；代码：`AlertDialog` 10、`TextButton` 12、`Checkbox` 2、`OutlinedTextField` 3、`DropdownMenu` 1、默认 `LinearProgressIndicator` 4、`CircularProgressIndicator` 1 | 对话框与表单件归 TON-1650 D12–D14；按钮按压水波纹见 M1；页图转圈见 E2 |
| A10 | 基线不齐 | 待修 | `light/explain-failed` | 「讲解这段」按钮和右侧编号小字不在一条基线上；随 A11 去掉编号后消失 |
| A11 | 占位文案、中英混杂、内部编号露出 | 待修 | `light/explain-failed`（`3f6e6f96…:p1:11`）、`light/exam-attempt`（`作答记录：local-d25dd1bb-…`）、`light/quiz`（「出题：选择」） | 用户看到的是哈希和 UUID。去向：讲解卡 P2；考试 P4；「出题：选择」改「题型：选择题」随 P4 一起看文案 |
| A12 | 开发者口吻的说明 | 待修 | `light/reader`；`TextbookReader.kt:312` | 页面底部常驻「页码按 PDF 原文件计算。」像开发备注。收进页码的悬停提示，或只在两种页码不一致时出现。去向：P1 |
| A13 | 一屏多个主按钮 | 待修 | `light/today` | 今天页三个按钮里两个是黑底主按钮，看不出先点哪个。只留一个主按钮，其余用纸面按钮。`WindowsHomeCards.kt` 本轮无人占用，但等 P0 的按钮手感合并后再动，免得两次改同一处：P3 之后另排 |

### 成熟度

**状态**

| # | 项 | 状态 | 截图 / 位置 | 说明与去向 |
| --- | --- | --- | --- | --- |
| E1 | 第一次打开 / 空书架 | 已对 | `TextbookShelf.kt:38-65` | 一句说明 + 「上传教材」，格式和存放位置写在下面 |
| E2 | 加载不转圈 | 待修 | `TextbookReader.kt:253`、`TextbookWorkspace.kt:120` | 页图加载用居中转圈；忙时顶部默认蓝色细条。改成纸面占位（同尺寸浅底页）和 token 色细条。去向：P1（阅读）；顶部细条在外壳文件，等 TON-1656 释放后并入 P5 |
| E3 | 出错给下一步 | 待修 | `light/explain-failed`；`TextbookWorkspace.kt:121`、`TextbookShelf.kt:142` | 讲解失败只有一行朱字，没有「重试」；工作区和书架错误仍用 `colorScheme.error`。去向：P2（讲解）；外壳和书架两处并入 P5、P3 |
| E4 | 长书名 / 长段落 | 未验证 | — | 截图测试只有短书名 `knowledge-recap`。盲评轮补一本长书名隔离样书 |
| E5 | 离线 | 已对 | `light/explain-failed`、`ask-unavailable` | 文案「暂时没连上 Mac，可继续阅读或稍后重试」是人话 |
| E6 | 讲解慢 | 已对 | `BookExplain.kt:220` | 按钮变「讲解中…」，不挡阅读 |

**键盘**

| # | 项 | 状态 | 位置 | 说明与去向 |
| --- | --- | --- | --- | --- |
| K1 | Tab 顺序 | 未验证 | — | 需 winbox 真窗口走一遍；派给 P1 交付时附 |
| K2 | 焦点环 | 待修 | `light/explain-failed`（鼠标点过的「讲解这段」留着黑框） | 鼠标点击后不该留环，只在键盘焦点时显示。TON-1650「以后再说」里也记了。去向：P0 |
| K3 | 方向键 / PageUp / PageDown 翻页 | 待修 | `TextbookWorkspace.kt:108-122` 只处理 Esc；配对阅读器 `reader/ReaderKeys.kt` 已有 | 本机教材阅读不能用键盘翻页。去向：P1（在 `TextbookReader.kt` 内处理，不碰外壳） |
| K4 | Esc 关闭 | 已对 | `Key.Escape` 6 处 | 阅读、批注、对话框都能 Esc |
| K5 | 快捷键看得见 | 待修 | 全局无提示组件 | 「上一页 / 下一页」等按钮悬停时写出快捷键。去向：P0 提供带快捷键的提示组件，P1 用上 |
| K6 | Ctrl+F | 不做 | — | 书内查找是新功能，本卡不加功能；记在 TON-1654「以后再说」 |
| K7 | Enter 提交 | 未验证 | — | 选段就问、配对码输入框 Enter 是否提交，随 P2 核 |

**鼠标**

| # | 项 | 状态 | 位置 | 说明与去向 |
| --- | --- | --- | --- | --- |
| M1 | 悬停状态 | 待修 | `shared/ui/.../v7/PaperButton.kt`；全模块悬停处理只有 3 处（`hoverable`/`onPointerEvent`） | 按钮、选项行没有悬停变化，按下是 Material 水波纹。改成悬停加一层 `selBg`/`zhuWash` 级别的浅底，按下略深，不要水波纹。去向：P0 |
| M2 | 指针形状 | 待修 | 全仓 `pointerHoverIcon` 0 处 | 可点的地方显示手形，正文显示 I 形。去向：P0（按钮）、P1（正文） |
| M3 | 提示与延迟 | 待修 | `Tooltip` 0 处 | 只有图标或缩写的按钮（`Aa`、侧栏）悬停约 0.5 s 出提示。去向：P0 |
| M4 | 右键菜单 | 未验证 | `SelectionContainer` 4 处 | Compose 默认右键菜单的文字是否中文、是否只有「复制」，需真窗口核 |

**排版**

| # | 项 | 状态 | 位置 | 说明 |
| --- | --- | --- | --- | --- |
| T1 | 中西文间距 | 已对 | `light/today`「第 1 页」「6 / 6 节」 | 数字两侧有空格 |
| T2 | 标点挤压、行首禁则 | 未验证 | — | 截图里没有折行到标点的例子；E4 的长段落样书一起看 |
| T3 | 等宽数字 | 已对 | `light/exam-attempt`「剩余 29:59」 | 计时和页码用 `numbers` 字体 |
| T4 | 页码、日期格式 | 已对 | 「第 1 页」「1 / 1」 | |
| T5 | 行长 | 已对 | `light/reader` | 正文栏约 32 个汉字，讲解卡更宽但在 90 字符内 |

**Windows**

| # | 项 | 状态 | 位置 | 说明与去向 |
| --- | --- | --- | --- | --- |
| W1 | 窗口与任务栏图标 | 待修 | `build.gradle.kts:101-116` 无 `iconFile`；`Window(...)` 无 `icon` | 现在是 Java 默认图标。图标已在本 PR 交付（第 2 节），接线归 TON-1656 |
| W2 | 窗口标题统一 | 待修 | 「Coach」「LerningCoach」「教材原文」三种 | 归 TON-1656 |
| W3 | 最小窗口尺寸 / 贴靠 | 待修 | 全仓 `minimumSize` 0 处 | 拖得很窄时侧栏和正文会挤坏。先在 1/2、1/3 贴靠下截图定最小值，再设 `window.minimumSize`。`WindowsTextbooks.kt` 被 TON-1656 占用，排在其后：P5 |
| W4 | 125/150/175% 缩放 | 未验证 | — | 截图测试固定 Density 1.5。P5 交付时在 winbox 改系统缩放各拍一张 |
| W5 | 滚动条 | 待修 | `verticalScroll` 多处、`Scrollbar` 仅 1 处 | 书架、讲解、考试长页面没有滚动条，看不出还有内容（`light/bookshelf` 底部被截断的书目即此）。去向：P3（书架）、P2（讲解）、P1（阅读）各自加，用同一个 V7 细滚动条样式（P0 提供） |
| W6 | 跟随系统明暗 | 未验证 | `WindowsTextbookHost.kt:103` 用 `isSystemInDarkTheme()` | 运行中切换系统主题是否即时跟随需真窗口核；P5 |
| W7 | 系统标题栏 | 已对 | 使用系统标题栏 | 不自绘，贴靠菜单可用 |

**动效**

| # | 项 | 状态 | 位置 | 说明 |
| --- | --- | --- | --- | --- |
| D1 | 时长与曲线一致 | 已对 | TON-1650 D10 | 走 `CoachV7` 动画 token |
| D2 | 减少动画 | 已对 | `V7Fonts.jvm.kt:136` | 读 Windows 设置 |
| D3 | 不跳布局 | 待修 | `light/explain-failed` | 失败提示插在按钮下方，把下面的段落往下推；随 P2 一起把状态行固定高度 |

### 机械计数（只报告，不当门槛）

main `941b08a2`，`desktopApp/src/main/kotlin/com/coach/desktop` 下 `rg -c`：

| 项 | 数 | 集中在 |
| --- | --- | --- |
| `Color(0x` 硬编码颜色 | 45 | 全在 `reader/`（配对阅读器）：ReaderNotes 13、ReaderBody 10、ReaderQuestions 9、ReaderWindow 6、ReaderPalette 4、LocalBooksReaderSource 2、ReaderShelf 1 |
| `N.sp` 硬编码字号 | 68 | ReaderNotes 25、ReaderBody 15、ReaderQuestions 15、ReaderWindow 7、ReaderShelf 5、TextbookReader 1 |
| `AlertDialog(` / `TextButton(` | 10 / 12 | |
| `OutlinedTextField(` / `Checkbox(` / `DropdownMenu(` | 3 / 2 / 1 | |
| `*ProgressIndicator(` | 5 | |
| `colorScheme.error` | 4 | |
| `pointerHoverIcon` / `Tooltip` / 右键菜单 / `minimumSize` | 0 / 0 / 0 / 0 | |

每张修复卡交付时在本表追加一列「修后」。

## 4. 修复分卡

按 `docs/desktop-lanes.md` 一泳道一卡。避开 TON-1650 第 13 刀（`books/TextbookPractice.kt`、`DesktopV7ScreenshotTest.kt`、`docs/acceptance/TON-1650-desktop-v7.md`）和 TON-1656（`WindowsTextbooks.kt`、`WindowsTextbookHost.kt`、`reader/ReaderWindowHost.kt`、`Main.kt`、`CoachDesktop.kt`、`desktopApp/build.gradle.kts`、`NotesExport.kt`、`shared/ui/.../v7/Desk.kt`）。

| 卡 | 范围 | 文件 | 状态 |
| --- | --- | --- | --- |
| P0 按钮手感 | M1 M2 M3 K2 K5，以及 V7 细滚动条样式 | `shared/ui/.../v7/PaperButton.kt`、新文件 `shared/ui/.../v7/V7Tooltip.kt`、`V7Scrollbar.kt`（jvmMain） | 本轮派 |
| P1 本机阅读 | K3 E2(页图) W5(阅读) M2(正文) A12 | `books/TextbookReader.kt` | 本轮派 |
| P2 讲解卡 | A10 A11(出处) E3(讲解) D3 W5(讲解) K7 | `books/BookExplain.kt`、`books/AnswerExplain.kt`、`books/BookSourceRow.kt` | 本轮派 |
| P3 书架 | A1(模式卡) E3(书架) W5(书架) | `books/TextbookShelf.kt` | 排队，P0 合并后 |
| P4 本地参考练习文案 | A7 A11(作答记录、出题) | `books/exam/LocalExamUi.kt`、`books/TextbookLearning.kt` | 排队，等 TON-1650 第 13 刀合并 |
| P5 窗口外壳 | W3 W4 W6 E2(顶部细条) E3(外壳错误) | `WindowsTextbooks.kt`、`WindowsTextbookHost.kt`、`books/TextbookWorkspace.kt` | 排队，等 TON-1656 合并 |

验证：每张卡附改前改后浅深截图；全部合并后，用同一组场景重做匿名盲评（Reviewer-K、QA 分别评），和 round0 对比。
