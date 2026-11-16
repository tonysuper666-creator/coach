## 这份清单是什么

Windows 桌面端每个主要界面和 V7「案头」设计包逐张对照后的偏差清单。截图由 `desktopApp/src/test/kotlin/com/coach/desktop/DesktopV7ScreenshotTest.kt` 生成，不进 Git，跑一次 `:desktopApp:test` 就重新出全：

- 原图：`desktopApp/build/screens/v7-desktop/<屏>/<明暗>/<界面>.png`
- 对照图：`desktopApp/build/screens/v7-desktop/compare/<屏>-<明暗>-<界面>.png`，左边是桌面原图（1:1），右边是设计包参考图缩到同高
- 屏：`1920x1080`、`2560x1440`，都按 Windows 150% 缩放（`Density(1.5)`）
- 界面：`bookshelf`（书架）、`today`（今天）、`course`（课程）、`reader`（阅读器）、`quiz`（做题）、`explain`（讲解卡）、`summary`（小结）、`recap`（知识回顾）、`reviews`（复习列表）、`review-question`（复习做题）
- 参考图：winbox `D:\agent\design\ui-v7\shots\inner-{week,read,quiz}-{light,dark}.png`（Fold8 内屏 2448×1848）。设计包没有桌面专用稿，所以只比体系问题（颜色、字体、纸面、留白、线条、文案），不比像素位置。

下面的像素坐标是原图坐标（以 1920x1080 为准），颜色用取色得到。token 来自 `shared/ui/src/commonMain/kotlin/coach/design/tokens/CoachV7.kt`。2560x1440 下问题一致，另外多一条 D11 里写的版心问题。

## 对得上的部分

- 桌面底色：十个界面的右下角和顶栏在浅色下都是 desk `#E8E2D6`，在深色下都是 `#0E0D0B`。测试日志 `V7_DESKTOP … canvas= header=` 逐张记了下来。
- 阅读器、做题、复习三处的纸面是 sheet `#FCFAF6` / `#1B1916`，对。
- 选中正确项后的松绿底色和对勾，和 `inner-quiz` 一致。
- 深色下的选项卡片是 card `#23201C`，对。

## 偏差

代码位置按 TON-1648 拆分后的 main（`desktopApp/src/main/kotlin/com/coach/desktop/` 下的相对路径）。「状态」一栏随每个 PR 更新。

| # | 类别 | 现象与证据 | 违反 | 代码位置 | 状态 |
|---|---|---|---|---|---|
| D1 | 字体回退错 / 标题不是宋 | 中文标题和正文全部落到系统黑体：书架标题、走法卡标题、「今天接着读」「本地练习与记录」「第6节 核糖体」「复习 1 题」「讲解」、阅读器标题和正文。拉丁字母（如 `knowledge-recap`）是 Literata，说明只缺中文。见 `compare/1920x1080-light-reader.png`、`-quiz.png` | 正文和标题应为 Literata + Noto Serif SC，界面字 IBM Plex Sans + Noto Sans SC | `shared/ui/src/jvmMain/kotlin/com/coach/ui/v7/V7Fonts.jvm.kt`。字体文件其实早已在 JVM classpath 上（`shared/ui/build.gradle.kts:22` 把 `androidMain/res` 加进 jvm 资源，`font/noto_serif_sc.ttf` 等都在），缺的是回退：Compose Desktop 把一组资源字体按字重只解析成一张字体，Skia 拿不到中文那张，中文就落到系统字 | **已修**（第 1 刀）：每张字体按字重注册进 Compose 用的同一个字体缓存，每条字链（拉丁在前、中文在后）登记成一个字族名，Skia 逐字回退到 Noto Serif SC / Noto Sans SC / 霞鹜文楷。拿不到缓存时退回原资源字体并打日志，`V7FontRegistryTest` 盯住这条路 |
| D2 | 纸面错 | 书架、今天、课程三处的内容直接放在 desk `#E8E2D6` 上，没有 V7 那张纸。见 `compare/1920x1080-light-bookshelf.png`、`-today.png`、`-course.png` | V7 内容都在 sheet 上，四周留桌面 | `WindowsHomeCards.kt`（今天、课程）、`books/TextbookShelf.kt`（书架） | **今天、课程已修**（第 2 刀）：两页内容放进一张 sheet 纸（`surface1`、`Radius.sheet`、`paperShadow("sheet")`），四周留桌面，最宽 900dp。**书架已修**（第 3 刀）：书架内容放进同一种 sheet 纸，最宽 1000dp，四周留桌面 |
| D3 | 多余线框 | V7 没有描边，桌面端到处是 1dp 线：走法卡有 `#E3DCCF` / `#34302A` 描边（bookshelf (322,634)）；顶栏底线 y=84，导航栏右线 x=114；「全部教材」上方、课程页、做题页都有分隔线；「全部教材」条目、今天「继续学」、课程「接着读」、小结「上一节」都是描边按钮（today (605,300)） | V7 只用色块和留白分层，不用描边 | `WindowsTextbookHost.kt`（原 `:81`、`:116`、`:120`）；`WindowsHomeCards.kt:54`；`books/TextbookShelf.kt:41`、`:44`；`books/TextbookLearning.kt:90`、`:140` | **外壳已修**（第 2 刀）：顶栏底线、导航栏右线去掉，栏、导航、页之间只靠桌面色和纸面分开；今天「继续学」、课程「接着读」改成 `PaperButton`。**书架已修**（第 3 刀）：三种走法卡和「全部教材」条目改成无描边的卡片色块（`surface2`、`paperShadow("card")`，带 V7 焦点环），「全部教材」上方的分隔线去掉，靠留白分开。**做题、小结已修**（第 5 刀）：节标题下的分隔线去掉，小结「上一节」改 `PaperButton`。**课程页本地练习已修**（第 9 刀）：「本地练习与记录」上方的分隔线去掉，里面的描边按钮、描边输入框换成纸面按钮和 V7 输入块，见 D13 |
| D4 | 顶栏 | 顶栏是衬线大字「Coach」（`titleMedium`）加「设置」。V7 是小号无衬线「Coach」、居中面包屑、右侧 Aa / 专心读。进阅读器、做题、复习后，`Desk` 又出一行「Coach 目录 返回书架」，「Coach」出现两次 | 顶栏只有一行 | `WindowsTextbookHost.kt:77`；`books/TextbookLearning.kt:71`、`books/TextbookReader.kt:154`、`books/TextbookReviewUi.kt:16`、`:48` 的 `Desk(...)` | **已修**（第 2 刀）：外壳顶栏改成和 `Desk` 同一条 48dp 栏，左边小号无衬线「Coach」（点一下回今天），中间是当前位置；进阅读器、做题、复习时外壳不画顶栏，只留 `Desk` 那一行，「Coach」只出现一次。「设置」移到导航栏底部，每页都点得到。外壳提供 `LocalDeskSelect`，`Desk` 里点「Coach」弹出的今天 / 课程 / 书架也能跳 |
| D5 | 文案 | 英文 learning 混在中文里：书架条目「已开启 learning」、阅读器按钮「开启 learning」、读后提示「要开启 learning 吗？」、无文字层提示「learning 只使用可提取的内容」、导入报错「开启 learning 需要带文字层的教材」；做题后另起两行「你选择：核糖体」「与原文一致。」，和选项上的对勾重复；讲解里的出处是正文中的「【短块号】」加一个描边按钮，V7 是正文后一行松绿下划线「出处 · 教材 p.x」 | 文案用人话，不混英文；出处样式 | `books/TextbookShelf.kt:48`、`:91`、`:113`；`books/TextbookReader.kt:173`；`books/TextbookController.kt:136`；`books/TextbookPractice.kt:40-41`；`books/AnswerExplain.kt:91`、`books/BookExplain.kt:227`（块号是主线要的，保留，只改呈现） | **书架部分已修**（第 3 刀）：条目「已开启 learning」改「在学」，读后提示改「要开始学吗？」，无文字层提示改「学的时候只用能提取出文字的部分」。**阅读器已修**（第 4 刀）：按钮「开启 learning」改「开始学」（在学时显示「继续学」），改成 `PaperButton`。**做题、出处已修**（第 5 刀）：「你选择」「与原文一致。」并成一行灰色小字，紧跟选项、右侧是「重新作答」，不再另起两行抢眼；出处改成主色下划线小字「出处 · 第 x 页 · “……”」（学习页）和「出处 · 短块号」（阅读器讲解），块号保留，只换呈现。导入报错在 `books/TextbookController.kt:136`，见「以后再说」 |
| D6 | 原文显示 | 阅读器里 markdown 标题带着井号显示成「## 第1节 细胞」。见 `compare/1920x1080-light-reader.png` | 标题应排成宋体标题，不露标记 | `books/TextbookReader.kt` 正文渲染 | **已修**（第 4 刀）：正文里 `#`–`######` 开头的行，井号和后面的空格做成透明、极小字号，标题用宋体，字号字重取 V7 typography：一级 `headlineLarge`（week），其余 `titleLarge`（section、Weight.title），随 Aa 小 / 中 / 大缩放。字符一个不删，`reader-text` 的文字和出处、批注的偏移照旧对得上原文；`MarkdownHeadingsTest` 锁住偏移，截图测试检查每个井号都被隐去、标题中号等于 section token、Aa 大 / 小时按 1.15 / 0.9 缩放 |
| D7 | 控件 / 暗色漏改 | 阅读器「页码」是 Material 描边输入框，带浮动标签；描边浅色 `#665F55`、深色 `#A0978A`（reader (1248,935)），在深色纸上很刺眼；「上一页」「下一页」是描边按钮 | 用纸面按钮和 V7 输入样式；深色用 line `#34302A` 一级的弱线 | `books/TextbookReader.kt:250-254` | **已修**（第 4 刀）：「上一页」「下一页」改 `PaperButton`，页码数字用数字字体；「页码」去掉 Material 描边框和浮动标签，改成左侧小字「页码」加一个 `surface2` 底、`borderSubtle` 弱线、8dp 圆角的输入块（带 V7 焦点环），只收数字；「前往」改 `V7TextButton`。工具栏「看原页 / 看文字」「选段提问」「讲解」「批注」一并改成 `V7TextButton` |
| D8 | 颜色不取 token | 复习条目是 `OutlinedCard`，底色 `#EBE9E4` / `#2A2925`，偏灰，不是 card（reviews (786,569)，左缘 x=152 有描边）；小结和知识回顾卡是 `#FCFAF4` / `#1D1B18`，放在 sheet `#FCFAF6` / `#1B1916` 上几乎看不见，原因是 `secondaryContainer` 叠 0.35 透明度；「讲解」块直接排在纸上，没有 PaperCard；阅读器高亮和当前批注行用 `secondaryContainer`，不是 `CoachV7.*.hl` | 卡片用 card，高亮用 hl，强调用 zhu / pine，不用 Material 动态色 | `books/TextbookReviewUi.kt:23`；`books/AnswerExplain.kt:157`、`books/KnowledgeRecap.kt:30`、`books/BookExplain.kt:221`；`books/BookLessonExplanation.kt:32`；`books/TextbookReader.kt:181`、`:237` | **阅读器已修**（第 4 刀）：文字视图的高亮、原页上的高亮框、当前批注行都改用 `CoachV7.Light.hl` / `CoachV7.Dark.hl`。**小结、知识回顾、讲解已修**（第 5 刀）：三张卡和逐书同意块都改成 `PaperCard`（`surface2`、`paperShadow("card")`），不再是 `secondaryContainer` 叠透明度；阅读器讲解的回答块同样改成 `PaperCard`；卡里的次要文字用 `fgSecondary`。**复习已修**（第 6 刀）：复习条目从 `OutlinedCard` 改成书架同款的无描边纸卡（`surface2`、`paperShadow("card")`、V7 焦点环，整张可点），复习原因（答错、猜的、到期）用朱色小字放在书名右侧，「课·走法」「上次作答」降成 `fgSecondary` 小字；复习题页去掉「作答记录」上方的分隔线，作答记录放进 `PaperCard`，「上次作答」「这题保留在复习里」降成灰色小字，「这题已复习。」用 `successFg` |
| D9 | 导航状态 | 从「今天」或「课程」进入阅读器、做题、复习后，左栏高亮仍停在「书架」。见 `compare/1920x1080-light-quiz.png` 左栏 | 高亮跟着来处走 | `WindowsTextbookHost.kt` 导航栏选中逻辑 | **已修**（第 2 刀）：从今天、课程打开的书，导航高亮留在来处，回到书架首页或另选一项才换；`WindowsTextbooksTest.railFollowsWhereTheBookWasOpenedFromAndEachPageHasOneBar` 盯住，同时检查每页只有一个「Coach」 |
| D10 | 动效 | 桌面端只有 `Desk` 里的纸张进场用 300ms / 180ms（`shared/ui/src/commonMain/kotlin/com/coach/ui/v7/Desk.kt:94`）；卡片展开（讲解、小结、知识回顾）没有 260ms `expandMs`，是直接出现；JVM 上 `rememberReducedMotion()` 固定返回 false，Windows「显示动画」关掉后不会停 | 300 / 260 / 180ms，减少动态时全部关掉 | 减弱动态：`V7Fonts.jvm.kt` `rememberReducedMotion()`；卡片展开：`books/AnswerExplain.kt`、`books/KnowledgeRecap.kt`、`books/BookLessonExplanation.kt`。代码层核对，截图看不出时长 | **减弱动态已修**（第 1 刀）：读 Windows「在 Windows 中显示动画」（`UserPreferencesMask` 第 5 字节 0x02 位），30 秒复查一次，只读不写；`-Dcoach.reducedMotion=true\|false` 可强制。**卡片展开已修**（第 5 刀）：讲解、小结、知识回顾、逐书同意出现时按 `CoachV7.Motion.expandMs`（260ms）和 V7 缓动从上往下展开并淡入，内容变长（讲解从「正在读取」到正文）也按 260ms 长高；减弱动态时直接出现。收起不做动画，点「继续做题」后卡片当场消失 |
| D11 | 版式 | 内容是一整栏，V7 是左目录、中间纸、右「页边」栏；做题标题叫「原文填空」（V7 没有这层小标题）；知识回顾插在题目上方，把题挤下去；同一屏有两个打开原文的入口：节标题下的「打开对应原文 · 第 x 页」和讲解后的「打开原文 · 块号」；2560x1440 下纸面贴左，右侧约五分之一空着（`compare/2560x1440-dark-quiz.png`），是 `Desk` 给页边栏留了 260–280dp，而本机教材页没有页边内容 | V7 三栏版心，纸面居中 | `books/TextbookLearning.kt:89`、`:93`；`books/BookSourceRow.kt:96`；`books/TextbookPractice.kt:23`；`shared/ui/src/commonMain/kotlin/com/coach/ui/v7/Desk.kt:122` | **做题部分已修**（第 5 刀）：「原文填空」小标题去掉（简答题只留一行灰色「简答自检」），题干用 `titleMedium`；知识回顾挪到这一题和讲解后面、小结前面，不再把题挤下去；节标题下的「打开对应原文」降成导语同一行的小字按钮「本节原文 · 第 x 页」，讲解后的出处改成下划线小字，两处不再一样重。**版心已修**（第 7 刀）：`Desk` 新增 `hasMargin`，桌面学习、做题、阅读器、复习四处没有页边内容的页面传 `false`，不再留空的页边栏、不出「出处与批注」开关，纸面在桌面里居中，最宽 1046dp（`colw` + `wRail` + 两侧 `mainPx`）；有页边内容的页面（安卓、iOS 和共享界面）默认 `true`，页边、窄屏抽屉和专心读照旧 |
| D12 | 阅读器讲解面板与选段就问 | 第 8 刀补拍后看到：讲解面板的 Mac 地址、配对码是 Material 描边输入框带浮动标签，「配对」是 Material 实心钮；逐块正文写死 `17.sp / 27.sp`，块号写死 `12.sp` 等宽字，「讲解这段」是描边按钮；配对失败、讲解失败用 `colorScheme.error` 红；逐书同意、出处弹窗用 Material `TextButton` 和默认字号。选段就问弹窗里段落是描边按钮，问题框是 Material 描边输入框，「暂时不能回答」用默认色，「回到阅读」是 Material `TextButton`。见截图场景 `explain-pairing`、`ask-unavailable` | 字号走 V7 typography，按钮用纸面按钮，输入块用纸面底加弱线，提醒用 zhu，不用 Material 描边和红 | `books/BookExplain.kt`（配对表单、逐块正文、同意与出处弹窗）；`books/BookPassageAsk.kt` | **已修**（第 8 刀）：两处输入框改成同一个 V7 输入块 `V7Field`（标签在上、`surface1`/`surface2` 底、`borderSubtle` 弱线、8dp 圆角、V7 焦点环，出错时线变 zhu）；「配对」「发送」「允许」改 `PrimaryButton`，「讲解这段」和段落选择改 `PaperButton`，「不允许」「关闭」「回到阅读」改 `V7TextButton`；逐块正文用 `bodyLarge`，块号用 `labelMedium` + 数字字体，次要说明用 `fgSecondary`；配对失败、讲解失败、超长提示、「暂时不能回答」都用 zhu；选中的原文和回答放进 `PaperCard`。配对、逐书同意、网络、原文偏移、500 字上限和所有 testTag 不变 |
| D13 | 课程本地练习 | 第 9 刀补拍后看到：课程页「本地练习与记录」上方有分隔线，入口、历次结果是描边按钮，计时分钟是 Material 描边输入框；作答页铺满整屏，没有纸面；选项是 Material 单选钮，简答是描边输入框；保存失败、冲突提示用 `colorScheme.error` 红；交卷、重新载入弹窗用 Material `TextButton`；题源页是默认字号的纯文本。见截图场景 `exam-entry`、`exam-attempt`、`exam-submit`、`exam-source` | 同一张纸面、纸面按钮、松绿选中、zhu 提醒，不用 Material 描边和红 | `books/exam/LocalExamUi.kt` | **已修**（第 9 刀）：作答、结果、题源放在同一张纸面上（最宽 900dp、`surface1`、`Radius.sheet`、`paperShadow`）；课程页分隔线去掉，题卷和历次结果放进 `PaperCard` / `PaperButton`，计时勾选是松绿勾选框加 V7 焦点环（不带 Material 灰底），分钟输入改 `V7Field`；选择题改 `AnswerOption`（单选语义），简答改 `surface2` 底、弱线、焦点环的输入块；「交卷」「确认交卷」改 `PrimaryButton`，「返回课程」「继续作答」「清空作答」「保留当前内容」等改 `V7TextButton` / `PaperButton`，「重新载入」用 zhu；保存失败、冲突提示用 zhu；倒计时用数字字体；题源页标题 `headlineMedium`，说明「本次题卷保存的教材片段 · 第 N 页」，原文放进 `PaperCard` 可选中。保存、计时、交卷、冲突恢复、冻结出处和所有 testTag 不变 |
| D14 | 设置与复习提纲导出弹窗 | 第 10 刀补拍后看到：设置弹窗里教材位置是一行裸路径，跟说明文字混在一起，「知道了」是 Material `TextButton`；导出弹窗的「周次 / 知识点」是 ●○ 字符拼的假单选，「PDF」是 Material 黑色勾选框，说明文字用 `fgPrimary`，保存位置是裸文字，「选择本机文件夹…」「导出」「关闭」是 Material 胶囊钮，导出失败和成功都用默认色或 Material 主色。见截图场景 `settings`、`notes-export`、`notes-export-point`、`notes-export-done`、`notes-export-failed` | 字段名在上、纸面块、松绿选中、zhu 提醒，按钮用纸面 / 主按钮，不用 Material 胶囊和黑勾 | `WindowsHomeCards.kt`（`HostSettings`）；`NotesExportDialog.kt` | **已修**（第 10 刀）：设置弹窗加小字段名「教材位置」，路径用数字字体放进 `surface1` 底、`borderSubtle` 弱线的纸面块，「知道了」改 `PaperButton`。导出弹窗按「范围 / 周次或知识点 / 格式 / 保存位置」四个字段排，字段名 `labelMedium` + `fgSecondary`；范围改成真单选（`selectable` + `Role.RadioButton`，选中 `pineWash` 底、松绿线和字，带 V7 焦点环）；周次 / 知识点下拉入口改 `PaperButton`；PDF 勾选改整行可点（`Role.Checkbox`）的松绿勾选框；保存位置放进纸面块，未选时灰字「尚未选择保存位置」；失败提示用 zhu，成功用 `successFg`，进度条松绿；「导出」改 `PrimaryButton`，「关闭」改 `V7TextButton`，「打开文件夹」改 `PaperButton`。设置读写、导出范围、格式、取消、失败反馈和所有 testTag 不变 |
| D15 | 阅读器正文与出处提示 | 第 11 刀对照后确认：阅读器正文 `reader-text` 写死 `19.sp / 31.sp`，Aa 选小、大时标题会变、正文不变；出处提示（「无法确认原句：……」）用 Material `colorScheme.error` 红。见截图场景 `reader` | V7 内屏正文 token `Size.body` 18 × `LineHeight.body` 1.85（约 33.3）。`inner-read` 参考图是 1224 宽的页面按 2 倍出图，正文行距量得 66px ÷ 2 = 33px，和 token 对得上。提醒用 zhu | `books/TextbookReader.kt` | **已修**（第 11 刀）：正文改用 `bodyLarge`（字体、深色降字重不变），字号、行距换成 `CoachV7.Size.body`、`LineHeight.body`，乘 Aa 倍数（小 0.9 / 中 1 / 大 1.15）。没有用 `ReadingSizes.body` 的 16sp，那是按 Fold8 可用窗口校准的，不是 V7 正文。出处提示改成 `v7Alert()`（zhu）。原文字符、井号隐去、高亮和出处偏移都不变；截图测试真点 Aa 小 / 中 / 大再回中，逐次核对正文字号和行距，并核对原文和所有标记的起止位置不变；`PracticeSourceMultiBlockTest` 核对出处提示是朱色 |
| D16 | 阅读器正文栏宽 | 第 11 刀对照后确认：桌面阅读器正文 `reader-text` 铺满整张纸，2560x1440 下一行六七十个字；换页时滚动位置沿用上一页，读到底翻页会落在下一页的中下部；打开出处只翻到那一页，被引的块常在首屏以下。见截图场景 `reader` | V7 正文栏 `.col{max-width:var(--measure)}`，`measure: 33em`（中号 18sp → 594dp，即 `Layout.colw`），在纸面内居中 | `books/TextbookReader.kt` | **已修**（第 12 刀）：正文栏最宽 33em（按正文字号换算，随 Aa 缩放：小 534dp / 中 594dp / 大 683dp），在纸面内居中；纸面比栏还窄时占满可用宽度。滚动仍在整张纸上，鼠标停在栏外两侧也能滚。换页从顶部读起；打开出处时滚到被引那一块。分页不受影响：导入时就把页切好存下（PDF 按原页；Markdown / 文本有页标记按标记，没有就由 `TextbookLibrary.paginate` 按文字块切；EPUB 逐章再按文字块切），页码、原文字符、出处与批注偏移都和栏宽无关。`ReaderMeasureUiTest` 用合成长段落（导入切成三页）在 1920x1080、2560x1440、窄窗口和 Aa 小 / 大 / 中下核对栏宽、居中、滚到底读到最后一个字、换页回顶、栏外滚轮，并核对出处页的标记偏移和滚入可见 |
| D17 | 简答作答框 | 第 12 刀后补拍简答题看到：教材做题页的简答作答框（`short-answer`）是 Material 描边输入框，字段名「写下你的理解」是浮动标签，嵌在描边上；聚焦时整圈描边加粗变色，和本地练习里已改好的简答输入块不是一个样子。见截图场景 `short-answer`、`short-answer-restored`、`short-answer-revealed` | V7 输入是 `surface2` 纸面色块加一道弱线，字段名放在框上方，聚焦只加焦点环 | `books/TextbookPractice.kt` | **已修**（第 13 刀）：字段名「写下你的理解」放在框上方一行 `labelMedium` 灰字；作答框改成 `surface2` 底、`borderSubtle` 一像素弱线、`Radius.btn` 圆角、内边距 12×10dp，聚焦时外圈 V7 焦点环，和本地练习的简答输入块同一套。输入仍是同一个文本框（中文输入法照旧），离开再回来恢复草稿、打字时不被回写覆盖、「对照原文自检」、「还需复习」「我已理解」和 `short-answer` 等 testTag 都不变 |

设置弹窗和笔记导出弹窗原先在清单之外，第 10 刀补拍后列为 D14。阅读器正文字号和出处提示原先记在「以后再说」，第 11 刀对照 V7 后列为 D15；正文栏宽随后列为 D16。简答作答框原先记在「以后再说」，第 13 刀补拍简答题后列为 D17。

## 修法和泳道

TON-1648 已拆完文件，泳道见 `docs/desktop-lanes.md`。每个 PR 从最新 main 切出，只碰一个泳道的文件，附改前改后对照图（同一测试重跑即得），并更新上表「状态」。顺序：

1. 字体与动效基础（`shared/ui` jvmMain）：D1、D10 中的减弱动态。**已做**。
2. 外壳与导航（`WindowsTextbookHost.kt`、`WindowsHomeCards.kt`）：D2 今天 / 课程、D3 外壳的线、D4、D9。**已做**。
3. 书架（`books/TextbookShelf.kt`）：D2 书架、D3 书架部分、D5「已开启 learning」。**已做**。
4. 阅读器（`books/TextbookReader.kt`）：D6、D7、D8 阅读器高亮、D5 阅读器按钮。**已做**。
5. 做题、讲解、小结、知识回顾（`books/TextbookLearning.kt`、`TextbookPractice.kt`、`AnswerExplain.kt`、`KnowledgeRecap.kt`、`BookLessonExplanation.kt`、`BookExplain.kt`、`BookSourceRow.kt`）：D3、D5、D8、D10 卡片展开、D11 做题部分。**已做**。
6. 复习（`books/TextbookReviewUi.kt`，另把 `books/TextbookShelf.kt` 的 `PaperTile` 从 private 改 internal 以便复用）：D8 复习条目。**已做**。
7. 三栏版心（D11）：`shared/ui/.../v7/Desk.kt` 加 `hasMargin`，桌面 `TextbookLearning.kt`、`TextbookReader.kt`、`TextbookReviewUi.kt` 传 `false`；回归测试 `DeskMarginLayoutTest`（有／无页边）和 `WindowsTextbooksTest`（实际宿主里纸面居中、无空页边）。**已做**。
8. 阅读器讲解与选段就问（`books/BookExplain.kt`、`books/BookPassageAsk.kt`）：D12。截图测试补拍 8 个场景：阅读器里点「选段提问」→ 选段落（`ask-pick`）→ 输入问题点「发送」得到「暂时不能回答」（`ask-unavailable`）→ 超过 500 字（`ask-too-long`）→ 点「回到阅读」关掉弹窗；讲解面板单独放在同一张纸上，用测试里的假 Mac（不连网、不存真凭据）：配对码错（`explain-pairing`）→ 配对成功 → 点「讲解这段」弹出逐书同意（`explain-consent`）→ 允许后出讲解（`explain-answer`）→ 点出处看到原文那一块（`explain-source`，断言原文和第一块一致）→ 关闭 → 再讲一段失败（`explain-failed`）。阅读器里真实的讲解面板仍走真实配对。**已做**。
9. 课程本地练习（`books/exam/LocalExamUi.kt`）：D13、D3 课程页那条线。截图测试补拍 8 个场景：课程页入口（`exam-entry`）→ 生成并开始作答（`exam-attempt`）→ 另一处改过同一份作答时的冲突提示（`exam-conflict`）和重新载入弹窗（`exam-reload`）→ 交卷确认（`exam-submit`）→ 结果（`exam-result`）→ 冻结题源（`exam-source`）→ 回课程看历次结果（`exam-history`）。另在 winbox 真窗口用真鼠标走一遍：课程 → 生成并开始练习 → 选一个选项（出现「作答已保存」）→ 滚到页底交卷 → 确认交卷 → 结果 → 查看题源 → 返回结果 → 返回课程 → 历次结果 → 打开这次记录，浅色、深色各 20/20。**已做**。
10. 设置与复习提纲导出弹窗（`WindowsHomeCards.kt` 的 `HostSettings`、`NotesExportDialog.kt`）：D14。先把导出弹窗的状态和面板抽成 `NotesExportState` / `NotesExportPanel`（外观不变），截图测试补拍 5 个场景：设置（`settings`）→ 导出按周（`notes-export`）→ 切到按知识点（`notes-export-point`）→ 导出成功（`notes-export-done`）→ 导出失败（`notes-export-failed`）。另在 winbox 真窗口用隔离书架走一遍：上传样例书 → Esc 关掉入书架提示 → 侧栏「设置」→ 弹窗里的教材位置是本次隔离目录 → 「知道了」关闭 → 关掉客户端重开 → 书还在 → 再开设置，位置不变 → Esc 关闭，浅色、深色各 16/16。导出弹窗要已配对、带知识点的课程窗口才打得开，本刀没走真窗口；QA 验收时用隔离合成台账在 winbox 原生窗口补验通过（选文件夹 / 取消、按知识点 Markdown、按周 PDF、保存失败后重试、关闭），真实配对课程入口仍未验证，见「以后再说」。**已做**。
11. 阅读器正文与出处提示（`books/TextbookReader.kt`）：D15。先对照 V7 定正文字号：取内屏 token 18 × 1.85，不取 Fold8 校准的 16sp；正文随 Aa 缩放，出处提示改 zhu。截图测试真点 Aa 小 / 大 / 中，核对正文、标题按 0.9 / 1.15 / 1 缩放，原文和标记偏移不变；两尺寸明暗改前改后图对照 `reader`。**已做**。
12. 阅读器正文栏宽（`books/TextbookReader.kt`）：D16。先核对分页路径：页只在导入时切好存下，PDF 按原页，其余由 `TextbookLibrary.paginate` 按文字块切（每块至多 4000 字，优先在 2000 字以后的换行处断），阅读器只显示已存页，栏宽不动分页、页码和偏移，所以只改显示。正文栏按 V7 `measure: 33em` 收住并居中；换页回顶、出处滚到被引块。新增 `ReaderMeasureUiTest`（两尺寸 + 窄窗口 × Aa 三档 + 翻页 + 出处）；两尺寸明暗改前改后图对照 `reader`。**已做**。
13. 简答作答框（`books/TextbookPractice.kt`）：D17。截图测试补拍简答题三个场景：写到一半（`short-answer`）→ 去「今天」再回到课程，草稿还在（`short-answer-restored`）→ 对照原文自检并点「我已理解」（`short-answer-revealed`）；作答框照本地练习的简答输入块改成纸面弱线，字段名在框上方。两尺寸明暗改前改后图对照这三个场景。**已做**。

## 怎么重跑

```sh
tools/dev/heavy ./gradlew :desktopApp:test
```

默认在 winbox 控制台会话里跑，设计包参考图在 `D:\agent\design\ui-v7\shots`，所以对照图会一起生成。在别处跑、参考图不在时，测试只出原图，并在日志里说明没有生成对照图。参考图换了位置，用 `-Dcoach.v7.refs=<目录>` 指定。

## 以后再说

- 导入报错「开启 learning 需要带文字层的教材」（`books/TextbookController.kt:136`）：在模型文件里，不属于书架泳道，等改控制器的那一刀顺手换成中文。
- 书架弹窗里「这次怎么读」「出题」的 `FilterChip` 未选中时有描边：桌面截图测试没有拍到这个弹窗，偏差表里也没列，先不动；要改需另加弹窗截图再对照 V7。
- 阅读器文字视图里，标题下方的空隙比上方略大（标题行变高，空行没变）：读起来不别扭，先不调；试过给标题段落单独设行高，空行会跟着变大，反而更松。
- 第 8 刀的「暂时不能回答」截图是当时未接回答服务时拍的历史结果。#314 后真实宿主使用配对问答：首次发送先询问逐书同意；允许后若没有配对，才显示暂时无法回答。已有逐书同意且已配对时会尝试回答；该历史截图不能证明现在的配对回答效果。
- `V7Field`（`books/BookExplain.kt` 末尾）目前只在讲解和选段就问里用；阅读器「页码」以后若要统一，可改用它。简答作答框第 13 刀照本地练习的简答输入块写（多行、字段名在上方），没有用单行的 `V7Field`。
- 知识回顾、讲解、小结卡收起不做动画：测试要求点「继续做题」后卡片当场消失，收起动画会让卡在 260ms 里还留着；要做得先和测试对齐。
- 课程页还在读本书练习时的占位（`WindowsTextbookHost.kt:136-137`）仍用 `colorScheme.error` 配「正在读取本书练习…」：在外壳文件里，不属于本地练习泳道；要改时换成 `fgSecondary`。
- `WindowsLocalExamUiTest.failedAcceptedAnswerBlocksCloseAndRetryPersistsIt` 在 `--rerun-tasks` 下偶尔失败，正常跑通过：本刀没改这条测试，也没改保存逻辑，先记下，等有人碰保存队列时一起看。
- 交卷弹窗里「继续作答」（`V7TextButton`）的文字基线比「确认交卷」（`PrimaryButton`）略低：和第 8 刀讲解面板的按钮一致，属于两个共用按钮的内边距，要改在 `shared/ui` 里统一调。
- 冻结题源页对 `.md` 书直接显示 `pageText`，会露出 `##` 标题记号：阅读器那边第 6 刀已去掉，这里是存档时保存的原文片段，要改得和阅读器共用同一个去记号函数。
- 结果页松绿下划线的「查看题源」OCR 时认时不认（屏幕上清楚可见）：原生走查按「参考答案」那行下方的位置点它，不影响用户。
- 复习提纲导出弹窗的原生窗口：QA 验收第 10 刀时用隔离合成台账在 winbox 补验过（选文件夹 / 取消、按知识点 Markdown、按周 PDF、保存失败后重试、关闭，核对了导出文件），通过。真实配对课程入口（已配对 Mac 服务端、带知识点的课程窗口）里点开导出仍未验证，接通隔离的课程服务端后补一次。
- 导出弹窗的周次 / 知识点下拉菜单仍是 Material `DropdownMenu` 的默认样式：截图测试拍不到展开态，先不动；要改时做成纸面菜单。
- 「选择本机文件夹…」等 `PaperButton` 用鼠标点过后会留一圈焦点环：这是 `shared/ui` 里 `PaperButton` 的行为，不只这一处，要改在 `shared/ui` 统一区分键盘焦点和鼠标焦点。
