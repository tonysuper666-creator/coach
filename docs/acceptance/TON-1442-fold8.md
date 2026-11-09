# Fold8 实际窗口的阅读与答题验收

旧 V7 的 1224×924 dp 图是设计示意，不能代表 Fold8 默认密度下的可用空间。本次以横屏 933×704 dp、竖屏 704×933 dp、外屏 411×900 dp 为截图窗口，密度均为 420 dpi（2.625）。

`Desk` 按实际可用宽度分栏：1100 dp 起显示周目录、正文、页边；700–1099 dp 显示正文和 260–280 dp 页边，周目录由按钮打开；不足 700 dp 显示单栏，页边由按钮打开底部抽屉。页边可收起。933 dp 横屏的正文纸面约 653 dp，扣除两侧留白后仍有约 605 dp，正文另有行宽上限。

字号通过 `ReadingSizes` 在运行时校准，保留原始 V7 导入包作为历史设计输入：正文/选项 16 sp、题干 17 sp、节标题 22 sp、周标题 26 sp、界面 13 sp、小标签 11.5 sp。Aa 提供小、中、大三档，默认中；应用内比例与系统字体缩放叠加。专心读使用同一套字号。

未作答时，学习页把节标题压成一行，追问收成旁边的按钮。外屏答题收起底部导航，保留顶栏返回入口，并去掉题卡重复的内边距。返回按钮显示调用方传入的上一级名称（例如「← 复习」），不受底部导航或专心读开关影响。追问展开后继续使用原来的草稿、输入法与发送逻辑。题组仍可滚动；进入新题组时先建立倒序列表的定位基准，再根据实际偏移对齐第一题顶部，避免列表已经到达边界时再次移动整段距离，把题干推出屏幕。主线答题页保留提交、猜测、幂等重试和讲解。

## 自动证据

`V7ScreenshotTest` 运行实际 TodayScreen、StudyScreen、MainlineScreen，使用合成账本和本机 HTTP fixture，不连接生产服务或订阅 CLI。学习题组复现原截图中的长节标题、长英文题干和四个长选项；原截图没有显示完整的第四项，因此第四项为同长度合成文案，不作课程答案依据。

每种窗口覆盖浅/深色、系统字体 1.0/1.15，以及周页、阅读、主线答题、学习答题、答题专心读、阅读专心读六种场景。共 72 个截图用例，另保存主线未提交截图 12 张。系统缩放使用 `RuntimeEnvironment.setFontScale`。原 1224 dp 的端到端数据测试仍验证宽窗口三栏行为，不作为 Fold8 尺寸标准。

检查题干和全部选项的未裁切边界是否完整落在实际滚动视口内，横拿正文宽度至少 520 dp。截图在任何手动滚动之前保存。交互回归覆盖目录抽屉、窄屏页边抽屉、字号改变后草稿保留、页边收起/展开、专心读与折叠切换，以及原有品牌菜单、导航和减弱动态效果。

本机运行 Android/shared domain/protocol/client 门禁；`shared:ui:jvmTest` 与 `server:*` 按运行环境约定交给 Mac 独立补跑。截图及本机测试通过仍需 Reviewer-K 对照三张原图逐张复核，并完成异厂独立验收后合并。APK 的 `versionCode` 保持 1，签名需核对为 `4435a6c41f1c4cbae89765961064df3c488f82a8dd5ae296dc9475a31a6d4e30`。

Windows 本轮通过 `tools/dev/heavy --local` 调用临时 PowerShell 启动器（随证据包提供），使用与远程 Windows runner 相同的 `Global\CoachHeavySlot0/1` 互斥槽、JDK 21、共享 Gradle 缓存和低优先级。启动前检查可用内存不低于 4096 MB，沿用仓库堆大小；不占用 Mac 构建锁。调用如下：

```sh
tools/dev/heavy --local powershell.exe -NoProfile -File ../run-build.ps1 :androidApp:testDebugUnitTest :androidApp:assembleDebug :shared:domain:jvmTest :shared:protocol:jvmTest :shared:client:jvmTest -Proborazzi.test.record=true
```

2026-11-09 本机复跑结果：命令退出 0，`BUILD SUCCESSFUL in 2m 10s`。Android 92 项、domain 63 项、client 74 项，合计 229 项，失败、错误、跳过均为 0；protocol 为 `NO-SOURCE`。新增 Android 回归覆盖 411/704/933/1224 dp 四种窗口及专心读开关，共 8 种组合，底部导航收起时「← 复习」仍可见且点击后回到复习队列。原 `ReviewEntryTest` 未改。输出 84 张真实窗口截图及原端到端 fixture 的 3 张补充图，证据清单记录每张图的原始像素尺寸、SHA-256 和源提交。

`apksigner verify --print-certs` 退出 0，证书 SHA-256 与上面的完整值一致；`aapt2 dump badging` 确认包名 `com.coach.android`、versionCode 1、versionName 0.1.0。候选 APK SHA-256：`5eb6d246c63fc3559392cd52e4152a9f700bfe9a5725af61209fe5c92395da5f`。新 head 的 Mac 门禁待独立复跑，视觉验收及合并后出包仍待执行，未宣称整卡验收通过。
