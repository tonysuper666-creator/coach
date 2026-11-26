# 阅读方式与翻页（TON-1671）

借的是 ereader.space 的做法和手感，外观仍是 V7「案头」。3D 倾斜视角、台灯光不做。

## 两种阅读方式

每本书记住用户选的方式，下次打开照旧；翻页和滑动之间来回切，停在同一句话上。第一次打开的默认值由 TON-1654「阅读设置」定，入口在阅读器的 Aa 里。

- **翻页读**：宽屏（≥ 1400dp，TON-1654 定了以它为准）两页对开，书脊在中间；窄屏单页。
- **竖着滑着读**：整本书连成一条往下滚。原版 PDF 页与页之间留桌面色的缝；文字版正文连续往下流。页边小字标原书页码，出处跳转滚到被引那块并框出来。

## 翻页效果（可选，每种都达同一标准才放出）

| 效果 | 状态 |
| --- | --- |
| 翻转 `PageTurnStyle.Flip` | 已做。页绕书脊转，翻起页投影、书脊阴影，过 90° 后背面淡淡透字（可关） |
| 滑动 `PageTurnStyle.Slide` | 已做。整个对开页整体平移 |
| 无 `PageTurnStyle.None` | 已做。直接换页 |
| 卷页 `PageTurnStyle.Curl` | 组件已做，阅读器菜单里还没有（达标前不放出）。拖哪个页角就从哪个角掀，松手过半翻过去、不到一半落回；键盘、点页边、滚轮翻页时页角走一条微微抬起的弧线。几何在 `PageCurlMath.kt`：把翻起的纸沿「页角原位—手指」连线的中垂线对折，背面在对开时是下一张的左页（字是正的），单页时是同一张纸的反面（字是反的、淡）。只用裁剪和矩阵，不用着色器，四端通用。还差：真窗口帧耗时、接进阅读器菜单 |

统一标准：跟手 p95 ≤ 16ms、明暗两套截图、键盘和触控板能翻、跨页选字不断、系统关「显示动画」时一律直接换页。

## 组件

`shared/ui` commonMain，`com.coach.ui.pageturn`：

- `PageTurnState(pageCount)`：`position`（左页下标，双页时为偶数）、`turn(forward)`、`jumpTo(page)`（中间页只快速翻过，最多 6 张）。
- `PageTurnView(state, paper, style, page = { i -> … })`：页里画什么由调用方给。
- `PageTurnMath.kt`：拖动进度、松手判定（过半或快甩翻过去，否则回弹）、位置规整，都是纯函数，有单测。
- 手势：拖页角、点页边、← → 空格 PageUp PageDown、Home End、触控板横扫、触屏拖动。键盘由 TON-1658 在 `TextbookReader` 里接时可把 `handleKeys` 设成 false，避免重复。
- 时长用 `CoachV7.Motion.pageMs`，跟手不设时长；`LocalV7ReducedMotion` 为真时直接换页。
- 翻起的页只做图层旋转，不重排文字。
- 卷页拖动时只读进度、不重组：页角位置在绘制阶段算。宽于两栏（>2 列）时卷页退回滑动。

## 学习功能跨页

拖选文字到页边停一下，调用方调 `state.turn(true)` 自动翻页，接着在新页上选。高亮、批注画在页内容里，所以翻起的页上照样显示。偏移约定不变，翻页只是显示。

## iPhone Duo（iOS 暂停，只写方案）

iOS 用 `UIPageViewController`：`transitionStyle = .pageCurl`；`spineLocation = .mid` 即双页对开，苹果「图书」就是这一套。

- 书本姿态（铰链竖）：书脊对准铰链，用 `reservedRegions` 取折痕位置，两页分在两块屏上。
- 展开横拿：双页。合上用外屏：单页。
- 手势、时长、跨页选字的规矩与 Windows 一致；「滑着读」用普通竖向滚动，不用页控制器。
- 四种效果在 iOS 上：卷页用系统自带；翻转、滑动用 `.scroll` 的横向切换或自绘；无 = 直接换页。
- iOS 恢复开发时照此做，并在 TON-1422 里引用本文。

## 功能对等验收表（李政 11-16：两种方式功能完全对等，不许哪种少东西）

两种阅读方式 × 两种视图逐格打勾，每格附 winbox 真窗口截图（真实教材截图只留本机）。

| 功能 | 翻页·原版 | 翻页·文字版 | 滑动·原版 | 滑动·文字版 |
|---|---|---|---|---|
| 插图（TON-1665）、表格公式知识框裁图（TON-1669） | | | | |
| 选字讲解、提问 | | | | |
| 高亮、批注显示和编辑 | | | | |
| 出处跳转（落到被引那块并框出） | | | | |
| 跨页、跨屏选字 | | | | |
| 目录跳转、搜索结果跳转 | | | | |
| 字号、字体切换后位置不丢 | | | | |
| 两种方式来回切停在同一句 | | | | |

当前全部未打勾：组件还没接入阅读器。

## 翻页模式下文字版的分页规则（插图不能少）

具体样子按 TON-1654「书页感」；分页由调用方（`BookPageLayout`）做，组件不管：

- 图和图注不拆开。
- 当前页剩余空间放不下这张图：图挪到下一页开头，空出的地方用图后面的正文补上，不留大块空白。
- 比一整页还高的图缩到一页内放下。
- 裁图块（公式、知识框）不从中间切开。
- 长表格按行拆到下一页，表头在新页重复。

## 滚动阅读组件用法（TON-1671，只在 shared/ui，未接阅读器）

`com.coach.ui.pageturn.ScrollReadView` / `ScrollReadState` / `ScrollReadPosition`（`ScrollRead.kt`、`ScrollReadMath.kt`）。

```kotlin
val state = remember(pageCount) { ScrollReadState(pageCount, ScrollReadPosition(page, fraction)) }
ScrollReadView(state, desk = deskColor, paper = paperColor) { i -> /* 画第 i 页，高度由内容定 */ }
state.position   // 视口顶边所在页 + 页内进度，滚过页边自动换成下一页
state.scrollTo(ScrollReadPosition(page, fraction))   // 目录、出处、搜索跳转；直接到位
```

- 页下标与 `PageTurnState.position` 同一套。翻页 → 滚动：`pageTurn.toScrollPosition()`；滚动 → 翻页：`position.toPageTurnPosition(pageCount, columns, align)`（书页 `alignToSpread=false` 时传 `align=false`）。要回到同一句，调用方在切换前后各用自己的句子锚点换算页和进度，组件保证页与页内进度不丢。
- 键盘：空格 / PageDown 滚一屏（留 10% 重叠）、Shift+空格 / PageUp 回一屏、Home / End、方向键；滚轮与触控板惯性走 LazyColumn 原生滚动；右侧细进度条。`handleKeys=false` 可交给宿主。
- 系统关闭「显示动画」（`LocalV7ReducedMotion`）：键盘滚一屏直接到位。
- 页间缝是 `desk` 色，宽 `gap`（默认 16dp）。
- 页边小字原书页码、出处框选，由调用方在页内容里画；`state.visiblePages` 给出视口内的页范围。
