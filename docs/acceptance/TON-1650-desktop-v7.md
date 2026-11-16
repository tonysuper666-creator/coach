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

| # | 类别 | 现象与证据 | 违反 | 代码位置 |
|---|---|---|---|---|
| D1 | 字体回退错 / 标题不是宋 | 中文标题和正文全部落到系统黑体：书架标题、走法卡标题、「今天接着读」「本地练习与记录」「第6节 核糖体」「复习 1 题」「讲解」、阅读器标题和正文。拉丁字母（如 `knowledge-recap`）是 Literata，说明只缺中文。见 `compare/1920x1080-light-reader.png`、`-quiz.png` | 正文和标题应为 Literata + Noto Serif SC，界面字 IBM Plex Sans + Noto Sans SC | `shared/ui/src/jvmMain/kotlin/com/coach/ui/v7/V7Fonts.jvm.kt:10` 只装了 literata、ibm_plex_sans、lxgw_wenkai、ibm_plex_mono；`noto_serif_sc.ttf`、`noto_sans_sc.ttf` 只在 `androidMain/res/font` |
| D2 | 纸面错 | 书架、今天、课程三处的内容直接放在 desk `#E8E2D6` 上，没有 V7 那张纸。见 `compare/1920x1080-light-bookshelf.png`、`-today.png`、`-course.png` | V7 内容都在 sheet 上，四周留桌面 | `WindowsTextbooks.kt`（今天、课程）、`books/TextbookWorkspace.kt`（书架） |
| D3 | 多余线框 | V7 没有描边，桌面端到处是 1dp 线：走法卡有 `#E3DCCF` / `#34302A` 描边（bookshelf (322,634)）；顶栏底线 y=84，导航栏右线 x=114；「全部教材」上方、课程页、做题页都有分隔线；「全部教材」条目、今天「继续学」、课程「接着读」、小结「上一节」都是描边按钮（today (605,300)） | V7 只用色块和留白分层，不用描边 | `WindowsTextbooks.kt:194` `HorizontalDivider`、`:229` `VerticalDivider`、`:233`、`:280` `OutlinedButton`；`books/TextbookWorkspace.kt:330`、`:758` |
| D4 | 顶栏 | 顶栏是衬线大字「Coach」（`titleMedium`）加「设置」。V7 是小号无衬线「Coach」、居中面包屑、右侧 Aa / 专心读。进阅读器、做题、复习后，`Desk` 又出一行「Coach 目录 返回书架」，「Coach」出现两次 | 顶栏只有一行 | `WindowsTextbooks.kt:190`；`books/TextbookWorkspace.kt:527`、`:689`、`:815`、`:847` 的 `Desk(...)` |
| D5 | 文案 | 书架条目写「已开启 learning」，中英混排；做题后另起两行「你选择：核糖体」「与原文一致。」，和选项上的对勾重复；讲解里的出处是正文中的「【短块号】」加一个描边按钮，V7 是正文后一行松绿下划线「出处 · 教材 p.x」 | 文案用人话，不混英文；出处样式 | `books/TextbookWorkspace.kt:334`、`:791-792`；`books/AnswerExplain.kt:91`、`books/BookExplain.kt:220-227`（块号是主线要的，保留，只改呈现） |
| D6 | 原文显示 | 阅读器里 markdown 标题带着井号显示成「## 第1节 细胞」。见 `compare/1920x1080-light-reader.png` | 标题应排成宋体标题，不露标记 | 阅读器正文渲染（`books/TextbookWorkspace.kt` 阅读器段） |
| D7 | 控件 / 暗色漏改 | 阅读器「页码」是 Material 描边输入框，带浮动标签；描边浅色 `#665F55`、深色 `#A0978A`（reader (1248,935)），在深色纸上很刺眼；「上一页」「下一页」是描边按钮 | 用纸面按钮和 V7 输入样式；深色用 line `#34302A` 一级的弱线 | `books/TextbookWorkspace.kt:623-627` |
| D8 | 颜色不取 token | 复习条目底色是 `#EBE9E4` / `#2A2925`，偏灰，不是 card（reviews (786,569)，左缘 x=152 有描边）；浅色做题选项卡片是纯白 `#FFFFFF`，不是 card `#FBF8F2`；小结和知识回顾卡是 `#FCFAF4` / `#1D1B18`，放在 sheet `#FCFAF6` / `#1B1916` 上几乎看不见，原因是 `secondaryContainer` 叠 0.35 透明度；导航栏选中项底色用的是 sheet | 卡片用 card，强调用 zhu / pine，不用 Material 动态色 | `books/AnswerExplain.kt:157`、`books/KnowledgeRecap.kt:30`、`books/BookExplain.kt:221`、`books/TextbookWorkspace.kt:823`（复习条目）、`:554`、`:610` |
| D9 | 导航状态 | 从「今天」或「课程」进入阅读器、做题、复习后，左栏高亮仍停在「书架」。见 `compare/1920x1080-light-quiz.png` 左栏 | 高亮跟着来处走 | `WindowsTextbooks.kt` 导航栏选中逻辑 |
| D10 | 动效 | 桌面端只有 `Desk` 里的纸张进场用 300ms / 180ms（`shared/ui/src/commonMain/kotlin/com/coach/ui/v7/Desk.kt:94`）；卡片展开（讲解、小结、知识回顾）没有 260ms `expandMs`，是直接出现；JVM 上 `rememberReducedMotion()` 固定返回 false，Windows「显示动画」关掉后不会停 | 300 / 260 / 180ms，减少动态时全部关掉 | `V7Fonts.jvm.kt:13`；`books/AnswerExplain.kt`、`books/KnowledgeRecap.kt` 卡片出现处。代码层核对，截图看不出时长 |
| D11 | 版式 | 内容是一整栏，V7 是左目录、中间纸、右「页边」栏；做题标题叫「原文填空」（V7 没有这层小标题）；知识回顾插在题目上方，把题挤下去，「原文填空」在一屏里出现两次；2560x1440 下纸面贴左，右侧约五分之一空着（`compare/2560x1440-dark-quiz.png`） | V7 三栏版心，纸面居中 | `books/TextbookWorkspace.kt:774` 以及做题页布局；`Desk` 宽度约束 |

## 修法和泳道

修改等 TON-1648 拆完文件、`docs/desktop-lanes.md` 合进 main 之后再动。每个 PR 只碰一个泳道的文件，附改前改后对照图（同一测试重跑即得）。按文件先后建议：

1. 字体与动效基础（`shared/ui` jvmMain）：D1、D10 中的 reduced motion。改一处全局生效，先做。
2. 外壳与导航（`WindowsTextbooks.kt` 或拆分后的 host 文件）：D2 今天 / 课程、D3 外壳的线、D4、D9。
3. 书架（拆分后的书架文件）：D2 书架、D3 书架部分、D5「已开启 learning」。
4. 阅读器：D6、D7。
5. 做题、讲解、小结、知识回顾：D3、D5、D8、D10 卡片展开、D11 做题部分。
6. 复习：D8 复习条目。
7. 三栏版心（D11）：牵涉 `Desk` 和多个页面，等前几刀合完再单独定。

## 怎么重跑

```sh
tools/dev/heavy ./gradlew :desktopApp:test
```

默认在 winbox 控制台会话里跑，设计包参考图在 `D:\agent\design\ui-v7\shots`，所以对照图会一起生成。在别处跑、参考图不在时，测试只出原图，并在日志里说明没有生成对照图。参考图换了位置，用 `-Dcoach.v7.refs=<目录>` 指定。
