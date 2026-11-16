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
| D3 | 多余线框 | V7 没有描边，桌面端到处是 1dp 线：走法卡有 `#E3DCCF` / `#34302A` 描边（bookshelf (322,634)）；顶栏底线 y=84，导航栏右线 x=114；「全部教材」上方、课程页、做题页都有分隔线；「全部教材」条目、今天「继续学」、课程「接着读」、小结「上一节」都是描边按钮（today (605,300)） | V7 只用色块和留白分层，不用描边 | `WindowsTextbookHost.kt`（原 `:81`、`:116`、`:120`）；`WindowsHomeCards.kt:54`；`books/TextbookShelf.kt:41`、`:44`；`books/TextbookLearning.kt:90`、`:140` | **外壳已修**（第 2 刀）：顶栏底线、导航栏右线去掉，栏、导航、页之间只靠桌面色和纸面分开；今天「继续学」、课程「接着读」改成 `PaperButton`。**书架已修**（第 3 刀）：三种走法卡和「全部教材」条目改成无描边的卡片色块（`surface2`、`paperShadow("card")`，带 V7 焦点环），「全部教材」上方的分隔线去掉，靠留白分开。做题、小结的线归第 5 刀；课程页「本地练习与记录」上方那条线在 `books/exam/LocalExamUi.kt:65`，不在本泳道，见「以后再说」 |
| D4 | 顶栏 | 顶栏是衬线大字「Coach」（`titleMedium`）加「设置」。V7 是小号无衬线「Coach」、居中面包屑、右侧 Aa / 专心读。进阅读器、做题、复习后，`Desk` 又出一行「Coach 目录 返回书架」，「Coach」出现两次 | 顶栏只有一行 | `WindowsTextbookHost.kt:77`；`books/TextbookLearning.kt:71`、`books/TextbookReader.kt:154`、`books/TextbookReviewUi.kt:16`、`:48` 的 `Desk(...)` | **已修**（第 2 刀）：外壳顶栏改成和 `Desk` 同一条 48dp 栏，左边小号无衬线「Coach」（点一下回今天），中间是当前位置；进阅读器、做题、复习时外壳不画顶栏，只留 `Desk` 那一行，「Coach」只出现一次。「设置」移到导航栏底部，每页都点得到。外壳提供 `LocalDeskSelect`，`Desk` 里点「Coach」弹出的今天 / 课程 / 书架也能跳 |
| D5 | 文案 | 英文 learning 混在中文里：书架条目「已开启 learning」、阅读器按钮「开启 learning」、读后提示「要开启 learning 吗？」、无文字层提示「learning 只使用可提取的内容」、导入报错「开启 learning 需要带文字层的教材」；做题后另起两行「你选择：核糖体」「与原文一致。」，和选项上的对勾重复；讲解里的出处是正文中的「【短块号】」加一个描边按钮，V7 是正文后一行松绿下划线「出处 · 教材 p.x」 | 文案用人话，不混英文；出处样式 | `books/TextbookShelf.kt:48`、`:91`、`:113`；`books/TextbookReader.kt:173`；`books/TextbookController.kt:136`；`books/TextbookPractice.kt:40-41`；`books/AnswerExplain.kt:91`、`books/BookExplain.kt:227`（块号是主线要的，保留，只改呈现） | **书架部分已修**（第 3 刀）：条目「已开启 learning」改「在学」，读后提示改「要开始学吗？」，无文字层提示改「学的时候只用能提取出文字的部分」。阅读器按钮归第 4 刀；做题、出处归第 5 刀；导入报错在 `books/TextbookController.kt:136`，见「以后再说」 |
| D6 | 原文显示 | 阅读器里 markdown 标题带着井号显示成「## 第1节 细胞」。见 `compare/1920x1080-light-reader.png` | 标题应排成宋体标题，不露标记 | `books/TextbookReader.kt` 正文渲染 | 未修 |
| D7 | 控件 / 暗色漏改 | 阅读器「页码」是 Material 描边输入框，带浮动标签；描边浅色 `#665F55`、深色 `#A0978A`（reader (1248,935)），在深色纸上很刺眼；「上一页」「下一页」是描边按钮 | 用纸面按钮和 V7 输入样式；深色用 line `#34302A` 一级的弱线 | `books/TextbookReader.kt:250-254` | 未修 |
| D8 | 颜色不取 token | 复习条目是 `OutlinedCard`，底色 `#EBE9E4` / `#2A2925`，偏灰，不是 card（reviews (786,569)，左缘 x=152 有描边）；小结和知识回顾卡是 `#FCFAF4` / `#1D1B18`，放在 sheet `#FCFAF6` / `#1B1916` 上几乎看不见，原因是 `secondaryContainer` 叠 0.35 透明度；「讲解」块直接排在纸上，没有 PaperCard；阅读器高亮和当前批注行用 `secondaryContainer`，不是 `CoachV7.*.hl` | 卡片用 card，高亮用 hl，强调用 zhu / pine，不用 Material 动态色 | `books/TextbookReviewUi.kt:23`；`books/AnswerExplain.kt:157`、`books/KnowledgeRecap.kt:30`、`books/BookExplain.kt:221`；`books/BookLessonExplanation.kt:32`；`books/TextbookReader.kt:181`、`:237` | 未修 |
| D9 | 导航状态 | 从「今天」或「课程」进入阅读器、做题、复习后，左栏高亮仍停在「书架」。见 `compare/1920x1080-light-quiz.png` 左栏 | 高亮跟着来处走 | `WindowsTextbookHost.kt` 导航栏选中逻辑 | **已修**（第 2 刀）：从今天、课程打开的书，导航高亮留在来处，回到书架首页或另选一项才换；`WindowsTextbooksTest.railFollowsWhereTheBookWasOpenedFromAndEachPageHasOneBar` 盯住，同时检查每页只有一个「Coach」 |
| D10 | 动效 | 桌面端只有 `Desk` 里的纸张进场用 300ms / 180ms（`shared/ui/src/commonMain/kotlin/com/coach/ui/v7/Desk.kt:94`）；卡片展开（讲解、小结、知识回顾）没有 260ms `expandMs`，是直接出现；JVM 上 `rememberReducedMotion()` 固定返回 false，Windows「显示动画」关掉后不会停 | 300 / 260 / 180ms，减少动态时全部关掉 | 减弱动态：`V7Fonts.jvm.kt` `rememberReducedMotion()`；卡片展开：`books/AnswerExplain.kt`、`books/KnowledgeRecap.kt`、`books/BookLessonExplanation.kt`。代码层核对，截图看不出时长 | **减弱动态已修**（第 1 刀）：读 Windows「在 Windows 中显示动画」（`UserPreferencesMask` 第 5 字节 0x02 位），30 秒复查一次，只读不写；`-Dcoach.reducedMotion=true\|false` 可强制。卡片展开未修，归第 5 刀 |
| D11 | 版式 | 内容是一整栏，V7 是左目录、中间纸、右「页边」栏；做题标题叫「原文填空」（V7 没有这层小标题）；知识回顾插在题目上方，把题挤下去；同一屏有两个打开原文的入口：节标题下的「打开对应原文 · 第 x 页」和讲解后的「打开原文 · 块号」；2560x1440 下纸面贴左，右侧约五分之一空着（`compare/2560x1440-dark-quiz.png`），是 `Desk` 给页边栏留了 260–280dp，而本机教材页没有页边内容 | V7 三栏版心，纸面居中 | `books/TextbookLearning.kt:89`、`:93`；`books/BookSourceRow.kt:96`；`books/TextbookPractice.kt:23`；`shared/ui/src/commonMain/kotlin/com/coach/ui/v7/Desk.kt:122` | 未修 |

清单之外、这次截图没覆盖的界面，后面各刀顺带对照：本地参考练习 `LocalExamPane`（`books/exam/LocalExamUi.kt:124`）、讲解配对面板 `BookExplainPanel`（`books/BookExplain.kt:134`）、选段就问 `BookPassageAsk`（`books/BookPassageAsk.kt:16`）、设置弹窗（`WindowsHomeCards.kt:75`）、笔记导出弹窗（`NotesExportDialog.kt:40`）。

## 修法和泳道

TON-1648 已拆完文件，泳道见 `docs/desktop-lanes.md`。每个 PR 从最新 main 切出，只碰一个泳道的文件，附改前改后对照图（同一测试重跑即得），并更新上表「状态」。顺序：

1. 字体与动效基础（`shared/ui` jvmMain）：D1、D10 中的减弱动态。**已做**。
2. 外壳与导航（`WindowsTextbookHost.kt`、`WindowsHomeCards.kt`）：D2 今天 / 课程、D3 外壳的线、D4、D9。**已做**。
3. 书架（`books/TextbookShelf.kt`）：D2 书架、D3 书架部分、D5「已开启 learning」。**已做**。
4. 阅读器（`books/TextbookReader.kt`）：D6、D7、D8 阅读器高亮、D5 阅读器按钮。
5. 做题、讲解、小结、知识回顾（`books/TextbookLearning.kt`、`TextbookPractice.kt`、`AnswerExplain.kt`、`KnowledgeRecap.kt`、`BookLessonExplanation.kt`、`BookExplain.kt`、`BookSourceRow.kt`）：D3、D5、D8、D10 卡片展开、D11 做题部分。
6. 复习（`books/TextbookReviewUi.kt`）：D8 复习条目。TON-1448 的复习 PR（#302）已合进 main，可以动了。
7. 三栏版心（D11）：牵涉 `Desk` 和多个页面，等前几刀合完再单独定。

## 怎么重跑

```sh
tools/dev/heavy ./gradlew :desktopApp:test
```

默认在 winbox 控制台会话里跑，设计包参考图在 `D:\agent\design\ui-v7\shots`，所以对照图会一起生成。在别处跑、参考图不在时，测试只出原图，并在日志里说明没有生成对照图。参考图换了位置，用 `-Dcoach.v7.refs=<目录>` 指定。

## 以后再说

- 课程页「本地练习与记录」上方的分隔线和里面的描边按钮、描边输入框（`books/exam/LocalExamUi.kt:65`、`:101`、`:106`、`:115`）：属于本地参考练习，不在这几条泳道里，等对照 `LocalExamPane` 时一起改。
- 导入报错「开启 learning 需要带文字层的教材」（`books/TextbookController.kt:136`）：在模型文件里，不属于书架泳道，等改控制器的那一刀顺手换成中文。
- 书架弹窗里「这次怎么读」「出题」的 `FilterChip` 未选中时有描边：桌面截图测试没有拍到这个弹窗，偏差表里也没列，先不动；要改需另加弹窗截图再对照 V7。
