# Windows：自己的教材，先读或直接学

Windows 桌面启动后进入同一个窗口，左侧是「今天 / 课程 / 书架」，右上角是设置。无需预先配置课程或连接 Mac。点击「上传教材」，选择自己的 PDF、EPUB、Markdown 或 UTF-8 文本。原文件会复制到本机书架；不会读取原有课程、教材目录、真题、配对凭据或服务端账本。

三处读的是同一本书、同一份进度：

- 「书架」管理教材本身：上传、选书、阅读器和学习页都在这里。
- 「今天」接着读这本教材：显示书签页和学习位置，「接着读」回到阅读器，「继续学」回到学习页。
- 「课程」把这本教材按节分：未开启 learning 时从书架自己的开启流程开始，已开启时回到当前那一节。课程不会从远端拉取，也不编造知识点映射。
- 书架为空时，今天和课程各自说明原因，不显示虚构进度；设置里写清教材和进度只在这台电脑。

- 「直接开始学」：按教材章节生成学习路线，默认先进入第一节的原文选择练习，答后再展开该节原文。
- 「先读」：先打开阅读器，再询问「要开启 learning 吗？」。「暂不开启」后仍可翻页、跳页，之后从书桌或阅读器开启。
- 书桌两扇门：「直接开始读」恢复阅读页码；「按进度继续学」恢复学习位置。
- 每节的「打开对应原文」打开同一本教材的对应页。返回学习后保留学习位置，也不覆盖单独阅读的书签。
- 出题统一选择「不出 / 选择 / 简答」，可在开始时或学习页的「出题」设置中修改。新书默认选择题；主动选择「不出」仍直接阅读，旧书已保存的选择也不覆盖。切换不重置进度，三种模式的作答分别保留。

PDF 显示原文件渲染的物理页，可切换文字；文字视图标出当前学习片段。EPUB 按 spine 阅读顺序提取正文，EPUB 和文本的页码为本阅读器分页。当前不呈现 EPUB 的版式、图片和交互。PDF 图片页仍可阅读，learning 只使用有可提取文字的页面；全扫描教材需要文字层才能生成路线，不会用文件名冒充教材内容。

这里使用 StudyCoach 的确定性课程路径：按来源文本块和标题分组、依次理解和回忆。当前学习页先展示可作答的原文练习，选择题答后展示该节教材片段；「打开对应原文」随时可选。选择题是依据原文的填空，简答是对照原文自检。没有调用 AI 生成讲解、语义题目或自动批改，也不将翻完一节计作掌握。没有题库的教材同样可以学习。

## 从封存 StudyCoach 取用的范围

只读取封存仓库 `tonysuper666-creator/studycoach` 的提交 `fe1ad5ce6983331ff11a6d5b8b3339bdff51f743`，将以下单教材路径移植为 Kotlin。没有解封仓库、编译旧 Mac 应用或启动旧服务。

- [extractors.ts](https://github.com/tonysuper666-creator/studycoach/blob/fe1ad5ce6983331ff11a6d5b8b3339bdff51f743/packages/course-builder/src/extractors.ts)：`deterministic-text-v1` 的标题、段落和顺序提取。
- [normalize.ts](https://github.com/tonysuper666-creator/studycoach/blob/fe1ad5ce6983331ff11a6d5b8b3339bdff51f743/packages/course-builder/src/normalize.ts)：文本块身份与来源保留。这里使用整本教材 SHA-256 和原文页内偏移；原文不做会改变定位的 NFKC 改写。
- [planner.ts](https://github.com/tonysuper666-creator/studycoach/blob/fe1ad5ce6983331ff11a6d5b8b3339bdff51f743/packages/course-builder/src/planner.ts)：`moduleGroups` / `deterministicPlan` 的标题分组、顺序路线、理解与应用引导。长章节按片段继续，保留全部来源。

PDF 提取和渲染采用 [Apache PDFBox 3.0.8](https://pdfbox.apache.org/3.0/migration.html)，使用 `Loader` 从本地文件读取。EPUB 的 XML 禁止外部实体与 DTD，只读书内正文，不打开外部资源。

## Windows 开发验证

开发启动必须显式设置独立的 `COACH_LIBRARY_DIR`，不能指向正式的 `~/Coach/books`。Windows 书架完全本地运行，不需要给开发启动虚构服务端地址或使用真实用户的配对令牌。其他桌面系统继续走原有入口。

在本机设置 JDK 21 和共用 Gradle 缓存，检查可用内存，再经 `tools/dev/heavy --local` 执行：

```sh
tools/dev/heavy --local ./gradlew :desktopApp:test --tests 'com.coach.desktop.books.TextbookLibraryTest' --tests 'com.coach.desktop.WindowsTextbooksTest' --tests 'com.coach.desktop.CourseConfigTest' --tests 'com.coach.desktop.DevRunGuardTest'
```

测试生成一本全新的三页生物教材，不使用用户教材、真题、账户或生产服务。真实 PDF 提取、原页渲染、导入事务、重复导入、独立阅读和学习进度、原文跳转、模式和作答恢复、扫描 PDF 和 EPUB spine/外部实体边界均在本机验证。UI 测试在离屏 Compose 中点按和输入，文件选择器注入测试教材路径，不操作桌面会话；截图输出到 `desktopApp/build/screens/windows-textbooks/`。

2026-11-13 在 winbox 实跑：上述四组测试共 18 项，失败、错误和跳过均为 0。另以独立空书架、不配置课程或配对信息，实跑 `:desktopApp:run -Pcoach.autoExitSeconds=5 -Pcoach.captures=startup@2 -Pcoach.captureDir=<任务内截图目录>`；自身原生窗口截图显示「我的书桌 / 上传教材」，随后退出码 0。此次 Direct3D 初始化有 Skiko 警告，回退后画面正常，原始日志保留；未修改系统或驱动。原生文件选择对话框未做桌面自动点按，完整交互由离屏 UI 测试覆盖。

不执行安装包、其他端构建、生产变更或集成合并。
