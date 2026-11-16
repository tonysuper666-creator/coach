## 第一阶段交付范围

教材从本机文件选择或拖进桌面窗口导入，正文在导入时切页并保存；阅读、学习出题、选段提问、出处和重新打开共用这份页文字及 UTF-16 偏移。原始文件按内容地址保存，不会因窗口大小或再次打开而重新分页。

产品决定已写入 `docs/PRODUCT_DECISIONS.md` 第 12 条：「阅读器兼容常见学习材料格式，不只 PDF 和 EPUB；2026-11-16 李政定」。该条同时记录第一阶段格式及第二阶段文字识别，后续卡按此执行。

| 材料 | 第一阶段处理 |
|---|---|
| TXT、TEXT | 严格解码 UTF-8、UTF-8 BOM、UTF-16 LE/BE（含无 BOM）、GB18030/GBK、Big5；按中文章/节、Chapter/Section、文字块分页；出处带章名 |
| Markdown、MARKDOWN | 标题分章后按原有文字块上限分页，保留原文偏移 |
| DOCX | 分页符优先；没有分页符时按标题和文字块；表格转文字，脚注追加到引用所在页 |
| PPTX | 按演示文稿关系表的顺序，一张幻灯片一页，追加备注；出处显示「第 N 张幻灯片」 |
| HTML、HTM | 离线解析，移除导航、脚本、样式、表单等；优先文章/主内容区；按标题分页 |
| MOBI、AZW3 | 无 DRM 的 PalmDB/MOBI：存储、PalmDOC、HUFF/CDIC；KF8 读取 FDST、INDX/TAGX 并重建 skeleton/fragment 正文，按章分页；混合版沿用 KF7 正文 |
| RTF、ODT | JVM 解析文字、标题；ODT 保留表格文字和脚注 |
| PDF、EPUB | 保留已有分页和已导入数据；补充密码/DRM 的明确提示 |

文件内容优先于后缀，例如 HTML 改成 `.txt` 或 `.pdf` 仍按 HTML 导入。格式说明改为「PDF、电子书、Word、幻灯片、网页和文本」。窗口根节点使用 Compose 的原生文件拖放目标，导入仍走同一个控制器。

不承诺自动恢复所有未知编码；不能可靠解码时提示另存 UTF-8。旧版 DOC/PPT、受保护材料、图片内的文字、复杂版式的视觉重现不在第一阶段范围。Office 提取正文及表格文字，不模拟 Word 的排版引擎。Kindle 的合成文件验证了解码路径，零售电子书的广泛兼容性还需后续公共样本覆盖。

## 依赖、许可和体积

全部新增解析代码及依赖运行于 JVM，不引入在线转换服务、Apache POI 或整套 Tika。

| 依赖/代码 | 许可 | 本机依赖文件大小 |
|---|---|---:|
| `org.jsoup:jsoup:1.24.1` | [MIT](https://github.com/jhy/jsoup/blob/jsoup-1.24.1/LICENSE) | 594,871 字节 |
| `com.ibm.icu:icu4j:78.1` | [Unicode-3.0 及其随附第三方声明](https://github.com/unicode-org/icu/blob/release-78.1/LICENSE) | 15,155,186 字节 |
| 四个隔离的 Tika MOBI 解码类，适配为本项目 Java 源码 | Apache-2.0；固定来源 `c2c3bcfdf1ccf7f2da429df2ebb00d0a6feee94e`，保留 ASF 头和 NOTICE | 无 Tika 运行时依赖 |
| Swing `RTFEditorKit` | 现有 JDK 模块，GPLv2 + Classpath Exception | 无新增 JAR |

原 PDFBox 3.0.8（Apache-2.0）保持不变。ICU 完整 LICENSE、Tika LICENSE/NOTICE 随应用资源一起打包到 `third-party/`。MOBI 来源路径及改动说明见 `desktopApp/src/main/resources/third-party/tika-mobi-NOTICE.txt`；KF8 重建、边界检查和端序工具为本项目实现。

同一台 Mac、ARM64、JDK 17、版本 1.0.1，用同一条 `tools/dev/heavy ./gradlew :desktopApp:packageDmg --no-watch-fs` 构建，均退出 0：

| 构建 | 实际 DMG 字节 | MB（十进制） |
|---|---:|---:|
| 改前 `01566f9614f621b1b2a1e170e2f0122ba7b6eee3` | 97,932,895 | 97.932895 |
| 本次最终代码及许可资源 | 112,858,238 | 112.858238 |
| 增量 | 14,925,343 | **14.925343（15.24%）** |

这是实际 DMG 对比，不是 JAR 大小相加。Windows MSI 本轮未测，不能把该数字当作 MSI 增量。没有安装、发布或上传安装包。

## 安全边界

- 导入前拒绝空文件及超过 256 MiB 的原文件；纯文本/RTF/Kindle 解压正文限 32 MiB。
- ZIP 内所有路径先检查：拒绝绝对路径、反斜线、冒号、`..`、重复条目；最多 10,000 条，每条解压不超过 16 MiB，总量不超过 64 MiB，实际读取再次限额。不把条目解压到文件系统。
- Office/ODT XML 禁止 DOCTYPE、外部实体、外部 DTD/schema 和 XInclude；关系路径只解析包内目标，不下载资源。
- Kindle 记录、字典、递归深度、索引和重建后的长度都有边界；损坏索引拒绝导入。PDF 密码、EPUB/MOBI DRM、加密 Office/ODT 给出可处理的人话提示。

## 样例与自动验证

测试材料全部为自己生成的短篇生物学文字，不含李政真实教材，也不访问现有教材目录。生成器为 `desktopApp/src/test/resources/books/formats/generate.py`，只用 Python 标准库，ZIP 时间戳固定。

`MultiformatBookTest` 覆盖 22 个成功样例：9 种文本编码、Markdown、DOCX、PPTX、HTML 及两个错误后缀、3 种 MOBI 压缩、真实索引结构的 KF8/AZW3、RTF、ODT、EPUB。每个样例以固定随机种子抽 3 个正文块，共 **66 处出处**，核对解析器原文、UTF-16 子串、解析目标有效性及控制器跳到原页。重新打开时逐页相等、原文件字节相等。

另有 **10 个拒绝案例**：空文件、损坏 DOCX、损坏 KF8 索引、XXE、ZIP 路径穿越、ZIP 单条超限、DRM MOBI、DRM EPUB、运行时生成的密码 PDF、运行时生成的 257 MiB 稀疏文件；拒绝后书架及暂存文件为空。补充字符测试核对分页和出处不会切开 UTF-16 代理对。现有 PDF/EPUB、出处和桌面测试保留。

```sh
tools/dev/heavy ./gradlew :desktopApp:test --tests com.coach.desktop.books.MultiformatBookTest --tests com.coach.desktop.books.BookSourceTargetTest --tests com.coach.desktop.books.TextbookLibraryTest
tools/dev/heavy ./gradlew :desktopApp:test
tools/dev/heavy ./gradlew :desktopApp:test --tests com.coach.desktop.MultiformatNativeWindowTest -Pcoach.nativeFormats=true
```

首条在 winbox 退出 0，12 项测试、0 失败。第一轮全量在 winbox 退出 0：409 项、0 失败、0 错误、5 跳过，执行 404 项；任务 `heavy-9462fce73f924252af6d6774316794c7`，快照 `58ce2c1e436c21b1358a173936cde4957fd27bb9`。

最终全量回归在 winbox 退出 0：94 个测试类、409 项、0 失败、0 错误、5 跳过（执行 404 项），Gradle 426.265 秒；任务 `heavy-96b6fece0a584a5d847112e9caf9cb1f`，快照 `cb437e365ece336fdc2c5fb4de50186325598031`。其中原生格式测试默认不运行，需要下节的显式开关；原有测试未删除、跳过或放宽。

## 真窗口和 V7 对照

原生验收需在空闲的 winbox 控制台**单独运行**，不要与其他 Robot/窗口测试并行。`MultiformatNativeWindowTest` 用自己创建的文件源窗口拖进实际 Compose 窗口，GBK TXT、DOCX、PPTX × 浅/深色共 6 条流程；点真实控件的语义操作完成阅读、选段提问、打开出处，核对请求与弹窗文字逐字相等，并断言章名/幻灯片的出处标签。

回答服务是显式注入的本地合成回执，用来验证格式到问答、出处界面的数据链路；**未验证真实订阅 AI 的回答质量或配对服务**。不会读取剪贴板、其他窗口、账号、真实教材，也不请求系统辅助功能权限。截图只捕获自己的 Skia 画面。

首次真拖放暴露 AWT 父窗口目标收不到 Compose 文件事件，已改用 Compose 拖放目标，随后 6/6 流程通过。补充幻灯片标签断言后与全量窗口测试并行的复测完成 5/6、最后一次拖放超时；最终验收改为独占顺序执行，不用放宽断言或重试来掩盖结果。

最终单独运行原生测试退出 0：1 项、0 失败、0 错误、0 跳过，六条流程 **6/6 通过**，包含章名和幻灯片标签断言；测试本体 33.041 秒，Gradle 49.004 秒。任务 `heavy-8b7c0a6b65ac407083576ab0df35c979`，快照 `cc802d1b24f9cc59eef94f99803d9b2efa20cc20`。GBK TXT 为 3 页、DOCX 为 2 页、PPTX 为 3 页，明暗两遍的请求和出处均逐字一致。

改前 V7 截图在基线 `01566f96` 用 `DesktopV7ScreenshotTest --no-build-cache` 重拍，改后随全量测试重拍。对照 `bookshelf` 明暗两组及现有 V7「案头」参考图，沿用纸面、字体、线条、留白及色彩；本次外观变动为格式说明和出处标签。原生截图是另一个证据，不能用离屏 V7 图替代真窗口拖放。

截图及命令日志随 Multica 交付评论附上，截图不进 Git。复跑路径：

- V7：`desktopApp/build/screens/v7-desktop/<尺寸>/<light|dark>/bookshelf.png`，参考对照在 `compare/`。
- 原生：`desktopApp/build/screens/formats-native/<light|dark>/<文件名>-{reader,reply,source}.png`。
- 精确快照、日志、测试 XML 与截图：`.coach-winbox/<任务>/`，其中 `artifacts.zip` 保留该轮独立结果，避免后一次测试覆盖本机同名输出。

## 第二阶段

第一阶段合并后再做扫描 PDF、PNG/JPG 的文字识别，另报三份中英混排样例的准确率、速度、Windows 中文识别包可用性及 Tesseract 模型/包体大小，比较后再实现。本 PR 没有 OCR、模型下载、移动端改动或在线转换服务。

第一阶段验收只覆盖上述格式导入和学习链路，整张 TON-1663 仍需第二阶段；不要因这个 PR 合并就宣称整卡完成。
