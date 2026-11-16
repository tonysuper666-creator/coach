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
| P7 今天与课程页 | A13 B2 B6（第 5 节） | `WindowsHomeCards.kt` | 排队，等 P0 合并（按钮手感先落地，免得同一处改两次） |
| P8 导出弹窗 | B8（第 5 节） | `NotesExportDialog.kt` | 排队，等 TON-1656 合并（它在改相邻的 `NotesExport.kt`） |

盲评第 0 轮的新增项（第 5 节）：B1 并入 P1（TON-1658）的下一刀；B4、B7 并入 P2（TON-1659）；B5 归 P0（TON-1657）的 K2，交付时在 `dark/ask-pick` 场景核一次。

验证：每张卡附改前改后浅深截图；全部合并后，用同一组场景重做匿名盲评（Reviewer-K、QA 分别评），和 round0 对比。

## 5. 盲评第 0 轮

素材 winbox `D:\agent\design\polish-blind\round0\`，对照表 `_key\round0.csv`（11-16 两份评分都交了以后才揭）。评审：Reviewer-K（TON-1661）、QA（TON-1662），各自独立评，没看对照表，也没看对方的评分。分数 5 = 像小工作室用心做的成熟产品，1 = 像 AI 生成的模板。截图来自 main `941b08a2`，1920×1080。

**基线**：Coach 12 张，Reviewer-K 平均 2.1（1–3 分），QA 平均 2.3（2–3 分）；参照 Readest 5 张，Reviewer-K 全是 5 分，QA 平均 4.2。两家都没有给任何一张 Coach 图打 4 分以上。第 1 轮用同一组场景、同一尺度复评，看每张图的分数变化。

**这轮不算严格盲评**：QA 指出原图里有看得见的品牌字样（Coach 顶栏字标、参照截图里的产品名），匿名化不完整；而且 5 张参照都来自同一款产品，风格一致，容易被整组认出来。分数只当作同场景复评的起点。第 1 轮的出题要求见本节末尾。

### 逐图

「对应」一列：已有清单项写编号；清单里没有的新问题编为 B1–B9，写在下表后面。

| 图 | Coach 场景 | K | QA | 两家点名的细节 | 对应 | 去向 |
| --- | --- | ---: | ---: | --- | --- | --- |
| 01 | `dark/reader` | 2 | 3 | K：正文底部半行字被页码区挡住；左下常驻「页码按……计算」。QA：短段落和下一个标题之间空得太多；底部分页单独占一整条 | A12、**B1** | P1（TON-1658）：A12 在本刀；B1 在下一刀 |
| 02 | `dark/course` | 2 | 2 | 两家：内容挤在左上，下面大约三分之二是空的深色面板。K：课程标题显示成 `knowledge-recap`，顶部几个按钮像没排完的流程 | **B2**、**B3** | B2 → P7；B3 记在第 1 轮样书要求 |
| 03 | `light/exam-attempt` | 2 | 2 | 两家：计时下面露出 `local-…` 作答记录 ID；选项 B 选中后只在最右边有个小黑点 | A11、A1 | P4 |
| 04 | `dark/notes-export` | 2 | 3 | K：鲜绿的 Material 大勾选框，和暗色纸面、宋体标题不像一套。QA：分组清楚，但底部说明在讲「Mac 公式服务」和「源码」 | A9、**B8** | P8 |
| 05 | `dark/ask-pick` | 2 | 2 | 两家：六段原文装成同样大小、同样颜色的圆角条，看不出哪里能点、点了会怎样。K：后面的「选段提问」按钮还留着两层焦点框 | **B4**、**B5**（K2） | B4 → P2（TON-1659）；B5 → P0（TON-1657） |
| 06 | `light/settings` | 2 | 2 | 两家：弹窗中间最显眼的是一长串带随机编号的临时目录路径，视觉中心落在机器内部细节上 | **B6** | P7 |
| 07 | `light/explain-failed` | 1 | 2 | 两家：每段「讲解这段」旁边、正文引用、出处链接里反复出现 `3f6e6f96…:p1:…` 这种编号。K：顶部内容被截掉 | A11、A10、**B9** | P2（TON-1659） |
| 08 | `light/explain` | 2 | 3 | K：讲解正文末尾带着 `【3f6e6f96…:p1:203】`。两家：四个选项是同样大小、带阴影的浮卡。QA：B 行用浅绿底加勾标出正确，这一点清楚 | A11、A1 | 编号 → P2；选项 → 做题泳道（TON-1650 第 13 刀之后） |
| 09 | `light/recap` | 2 | 3 | 两家：「这一轮的知识小结」和「小结」两块叠在一起，两组「继续 / 完成」按钮分在两张卡底部。K：页面从半句话开始 | **B7**、**B9** | P2（TON-1659） |
| 10 | `light/bookshelf` | 3 | 2 | 两家：三个学习入口是同宽、同字号、同阴影的白卡，像功能模板，不像书的集合。K：「全部教材」最下面一条被截掉，看不出还有内容 | A1、W5 | P3 |
| 11 | `light/today` | 2 | 2 | 两家：大半屏空白，只有三个按钮挤在左上。QA：「接着读」和「复习」都是深色实心按钮，看不出先点哪个 | A13、**B2** | P7 |
| 12 | `light/quiz` | 3 | 2 | 两家：四个选项都是同宽、同高、同阴影的卡片，没选的也像四张独立卡片。QA：状态、标题、说明、题干、辅助操作之间一层层留白，密度没有变化 | A1、A2 | 做题泳道（TON-1650 第 13 刀之后） |

参照 13–17（Readest：批注、深色、脚注浮层、主题、竖排查词）两家给 4–5 分，点名的做法：选中后贴着选区出现的分组工具条；目录用书签和页码标出当前位置；主题直接用真实底色预览，当前项实线、新建项虚线；查词浮层用小尖角指向那个词，并且用同一种纸色。这些做法已经记在第 1 节的参照里，本轮不新增。

### 新增待修项

| # | 项 | 截图 / 位置 | 改法 | 去向 |
| --- | --- | --- | --- | --- |
| B1 | 阅读页底部半行字被页码栏挡住；标题前留白过大 | `dark/reader`；`books/TextbookReader.kt` | 正文区的底边留出页码栏的高度，最后一行完整显示或整行推到下一页；Markdown 标题上方的间距用 V7 的节间距，不叠加段落间距；分页按钮和页码放进同一条底栏 | P1（TON-1658）验收通过后，作为下一刀交回同一个人 |
| B2 | 今天页、课程页内容挤在左上，下面大片空白 | `light/today`、`dark/course`；`WindowsHomeCards.kt` | 不加内容，只重新排：主卡在主区域内居中，宽度有上限（和阅读正文栏同一个上限）；「今天接着读」的书名、进度、下一步按从大到小三级排开，不要把三个按钮堆在一起 | P7 |
| B3 | 书名直接用文件名（截图里是 `knowledge-recap`） | `dark/course`；`books/TextbookLibrary.kt:132` 没有元数据时用文件名 | 截图里的是测试样书的文件名，不是真书；真实用户的文件名也可能是 `econ_ch3_final` 这种。本卡不改取名规则（属于新功能）：第 1 轮换成中文长书名的隔离样书重拍（同 E4）；把 `-` `_` 显示成空格记在「以后再说」 | 第 1 轮样书 |
| B4 | 选段就问：六段原文是同样的圆角条，看不出怎么选 | `dark/ask-pick`；`books/BookPassageAsk.kt` | 改成纸面上一列可点的行：行首用数字字体写段号或页码，悬停加浅底（用 P0 的悬停），选中用松绿线和 `pineWash` 底，弹窗顶部一句「点一段，再问一句」 | P2（TON-1659） |
| B5 | 弹窗后面的「选段提问」留着两层焦点框 | `dark/ask-pick`；`books/TextbookReader.kt:181` 的 `V7TextButton` | 就是 K2：鼠标点过之后不留焦点环 | P0（TON-1657）交付时在这个场景核一次 |
| B6 | 设置弹窗最显眼的是长路径 | `light/settings`；`WindowsHomeCards.kt:110` | 路径降为 `fgSecondary` 小字；用户主目录部分缩写成「用户文件夹」，后面照原样写（例如「用户文件夹\Coach\books」），完整路径仍然可以选中复制。截图样例改用真实形状的路径，不用测试临时目录 | P7 |
| B7 | 「这一轮的知识小结」和「小结」两块叠在一起 | `light/recap`；`books/AnswerExplain.kt:162` 的 `LessonSummaryCard`、`books/KnowledgeRecap.kt:32` | 什么时候出小结、写什么不变（不改学习逻辑），只改呈现：两块同时出现时，标题分别写「这一节」和「这六题学到的」，层级拉开；「继续 / 完成」按钮只在最下面的那张卡里放一组 | P2（TON-1659） |
| B8 | 导出弹窗：勾选框像 Material 默认件；说明在讲「Mac 公式服务」「源码」「账本快照」 | `dark/notes-export`；`NotesExportDialog.kt:105`、`:149`、`:176`、`:190` | 勾选框按 D14 已改成松绿，盲评仍然觉得刺眼：改成和 V7 选项同一套的小方框（细线框，选中时松绿填充，勾用纸色），不用 `CheckboxDefaults`；说明改成人话，例如 `:190` 改成「PDF 里的公式由你的 Mac 画出；画不出来的会照原样写出来并标明。」，`:149` 去掉「账本快照」 | P8 |
| B9 | 长页面截图从半句话开始、顶部被截掉，看不出上面还有内容 | `light/explain-failed`、`light/recap` | 这有一部分是截图取景造成的（测试里 `performScrollTo` 滚到卡片中间），但用户滚动后看到的也是这样，而且页面没有滚动条（W5）。产品这边并进 W5 的讲解、做题滚动条；截图这边按第 1 轮要求从卡片顶部取景 | P2（W5）、第 1 轮截图集 |

### logo（两家意见，只算模型意见）

| 方向 | K：AI 感 / 成熟度 | QA：AI 感 / 成熟度 | 点名理由 |
| --- | --- | --- | --- |
| A「页边 · 定稿」（已选） | 2 / 4 | 3 / 3 | K：纸、页边线、朱印有明确的构成逻辑，绿竖线加右下朱印有不对称的记忆点，但圆角外框和横线是常见的图标写法。QA：像三组现成符号拼在一起，朱印和页边线抢视觉中心，主形不够集中 |
| B「朱印」 | 3 / 4 | 2 / 4 | K：红底、双线框加书本，接近模板化的阅读应用图标。QA：线条语言统一，成熟度最高，但书本符号常见 |
| C「选项即课本」 | 4 / 3 | 4 / 3 | 两家：像通用的列表或表单符号，最弱 |

结论：**继续用 A**。原定规则是「两家都把 A 评得明显差于 B 才改」：K 认为 A 不比 B 差（AI 感更低、成熟度持平），QA 认为 B 更好，两家意见不一致，所以不改。另外 B 和 Apple Books 撞脸的问题（第 2 节）仍然存在。QA 说的「朱印和页边线抢中心」记在「以后再说」：之后可以在 A 里把朱印再缩一点、让页边线做主形，不换方向；要改的话，等第 1 轮盲评再一起评。

### 第 1 轮出题要求

全部修复卡合并后，在同一组场景上重拍，由同两位评审复评：

1. 遮掉品牌标识：Coach 顶栏字标、窗口标题、参照截图里的产品名和 logo，全部用中性色块盖住。出题的人先自查一遍，再交给评审。
2. 参照至少来自两款产品，不再只用 Readest，避免按风格被整组认出来。
3. 取景统一：长页面从卡片顶部截起；同一场景改前、改后用同一个滚动位置。
4. 样例数据像真的：用中文长书名的隔离样书（同时补上 E4、T2）；设置页用真实形状的路径，不用测试临时目录。教材原文仍然不进 Git、评论和附件。
5. 图号重新打乱；对照表仍然只放在 winbox `_key\`，两份评分都交了才揭。
