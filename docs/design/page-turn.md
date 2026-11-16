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
| 卷页（纸张卷曲） | 未做。用 Skia RuntimeEffect 或网格变形；达标前不出现在选项里 |

统一标准：跟手 p95 ≤ 16ms、明暗两套截图、键盘和触控板能翻、跨页选字不断、系统关「显示动画」时一律直接换页。

## 组件

`shared/ui` commonMain，`com.coach.ui.pageturn`：

- `PageTurnState(pageCount)`：`position`（左页下标，双页时为偶数）、`turn(forward)`、`jumpTo(page)`（中间页只快速翻过，最多 6 张）。
- `PageTurnView(state, paper, style, page = { i -> … })`：页里画什么由调用方给。
- `PageTurnMath.kt`：拖动进度、松手判定（过半或快甩翻过去，否则回弹）、位置规整，都是纯函数，有单测。
- 手势：拖页角、点页边、← → 空格 PageUp PageDown、Home End、触控板横扫、触屏拖动。键盘由 TON-1658 在 `TextbookReader` 里接时可把 `handleKeys` 设成 false，避免重复。
- 时长用 `CoachV7.Motion.pageMs`，跟手不设时长；`LocalV7ReducedMotion` 为真时直接换页。
- 翻起的页只做图层旋转，不重排文字。

## 学习功能跨页

拖选文字到页边停一下，调用方调 `state.turn(true)` 自动翻页，接着在新页上选。高亮、批注画在页内容里，所以翻起的页上照样显示。偏移约定不变，翻页只是显示。

## iPhone Duo（iOS 暂停，只写方案）

iOS 用 `UIPageViewController`：`transitionStyle = .pageCurl`；`spineLocation = .mid` 即双页对开，苹果「图书」就是这一套。

- 书本姿态（铰链竖）：书脊对准铰链，用 `reservedRegions` 取折痕位置，两页分在两块屏上。
- 展开横拿：双页。合上用外屏：单页。
- 手势、时长、跨页选字的规矩与 Windows 一致；「滑着读」用普通竖向滚动，不用页控制器。
- 四种效果在 iOS 上：卷页用系统自带；翻转、滑动用 `.scroll` 的横向切换或自绘；无 = 直接换页。
- iOS 恢复开发时照此做，并在 TON-1422 里引用本文。
