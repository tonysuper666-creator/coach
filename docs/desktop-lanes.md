# Windows 桌面端开发泳道

用途：按用户看到的功能分配文件，让书架、阅读、提问和做题可以同时开发。以下路径均相对
`desktopApp/src/main/kotlin/com/coach/desktop/`。`books` 包负责本机教材主线；`reader` 包
保留配对阅读器，也给本机教材复用键盘、阅读会话和批注组件。它们不是两套可互换的数据模型。

## 功能归属

| 泳道 | 包与主文件 | 边界与对应回归 |
| --- | --- | --- |
| 窗口生命周期与导航外壳 | `com.coach.desktop`：`WindowsTextbooks.kt`、`WindowsTextbookHost.kt`；`books/TextbookWorkspace.kt` | 窗口创建/关闭、今天/课程/书架切换、页面路由、跨页记忆与焦点请求。`WindowsTextbooksTest`、`WindowsDraftCloseTest`、`ReaderFocusTicketEffectTest`。仅导航或生命周期变更才占用这些文件。 |
| 今天 / 课程主页卡片 | `com.coach.desktop`：`WindowsHomeCards.kt` | 主页卡片、空状态、设置弹窗的呈现；不拥有书架或阅读页。`WindowsTextbooksTest`。 |
| 书架与导入提示 | `com.coach.desktop.books`：`TextbookShelf.kt`；`com.coach.desktop.reader`：`ReaderShelf.kt` | 本机书目、入口选择、导入/读后提示；配对书目列表。`WindowsTextbooksTest`、`WindowsLearningPathsTest`。 |
| 原文阅读 | `com.coach.desktop.books`：`TextbookReader.kt`；`com.coach.desktop.reader`：`ReaderBody.kt`、`ReaderWindow.kt`、`ReaderPalette.kt` | 本机页图/文字、翻页、原句标记、阅读焦点；配对正文绘制、阅读分栏与工具栏。`ReaderFocusTicketEffectTest`、`ReaderWindowTest`、`ReaderRemoteTest`。普通正文样式改动只需对应的 reader 文件；工具栏/分栏变更才占用 `ReaderWindow.kt`。 |
| 选段就问与讲解卡 | `com.coach.desktop.books`：`BookPassageAsk.kt`、`BookLessonExplanation.kt`、`BookExplain.kt`、`BookSourceRow.kt` | 段落选择、提问草稿、请求/回执、讲解呈现与引用行；页面只通过已有函数调用。`WindowsPassageQuestionLimitTest`、`BookLessonExplanationReplyTest`、`BookExplainTest`。 |
| 做题与课程目录 | `com.coach.desktop.books`：`TextbookLearning.kt`、`TextbookPractice.kt`；`com.coach.desktop.reader`：`ReaderQuestions.kt` | 按节学页面、目录、原文依据练习；配对阅读器的关联题目栏。`WindowsLearningEntryTest`、`WindowsLearningPathsTest`、`WindowsTextbooksTest`。 |
| 答后讲解、小结与回顾 | `com.coach.desktop.books`：`AnswerExplain.kt`、`KnowledgeRecap.kt` | 作答后的讲解、小结卡与知识回顾。`AnswerExplainCardTest`、`KnowledgeRecapTest`、`WindowsLearningEntryTest`。 |
| 本机复习队列 | `com.coach.desktop.books`：`TextbookReviewUi.kt`、`TextbookReviews.kt` | 到期列表与同一道题的再次作答；复习持久化仍由原 controller/store 管理。`WindowsLocalBookReviewTest`、`TextbookReviewPersistenceTest`。 |
| 批注 | `com.coach.desktop.reader`：`ReaderNotes.kt`、`NoteDraftStore.kt`、`NoteStatus.kt` | 阅读页批注列表、编辑与冲突提示、本机批注草稿；两个阅读入口复用同一 `NotesSection`。`ReaderRemoteNotesWindowTest`、`NoteDraftPersistenceTest`、`DraftIoHintTest`。 |
| 本地参考练习 | `com.coach.desktop.books.exam`：`LocalExamUi.kt`、`LocalExamController.kt`、`LocalExamStore.kt`、`LocalExamPaper.kt` | 已有独立模块，课程页通过现有入口调用。`WindowsLocalExamUiTest`、`LocalExamStoreTest`。 |

派卡时同时列出需要改的 `desktopApp/src/test` 文件。不同泳道若需要
修改同一个测试文件，也属于文件重叠，先安排顺序。

## 共享契约与需要串行的改动

- `TextbookController.kt`、`TextbookLibrary.kt`、`TextbookCourse.kt` 等模型/持久化文件仍为
  多页共用。普通界面卡使用已有参数和回调；确需改状态或存储契约时，单独占用相关文件并说明受影响泳道。
- `BookLearningSupport.kt` 只放讲解/选段请求、回执和 provider 接口；提问与讲解界面分别在
  `BookPassageAsk.kt` 和 `BookLessonExplanation.kt`。改传输/授权还需声明实际 provider 文件的占用。
- `AnswerDrafts.kt` 是宿主拥有的作答写盘队列，不能挪回随页面卸载而取消的 scope；更改队列归属须与外壳协调。
- `ReaderSession.kt` 保留完整会话状态与行为（约 500 行），`ReaderInput.kt` 负责 Compose 按键适配，
  `ReaderKeys.kt` / `ReaderState.kt` 保留原输入和状态契约。会话刻意不拆成多个状态持有者；
  变更其行为需与阅读、题目、批注泳道协调。`ReaderFixture.kt` 只含明确标注的自造样张。
- `ReaderRemote.kt`、`ReaderHostController.kt`、`LocalBooksReaderSource.kt` 等数据接线仍按实际文件
  申领，不因界面泳道不同而允许同时修改。课程配对入口 `CoachDesktop.kt` / `DesktopCourseHost.kt`
  与本机教材外壳分开，不能将两者的身份或数据源混用。

拆分只移动原有顶层声明，包名、函数调用、`remember` / effect 的位置与 key、状态拥有者、测试标签、
文案和回调顺序保持不变。跨文件调用需要的少量 `private` 声明改为模块内 `internal`，不开放公共 API。

## 派卡与验收

1. 卡正文列出泳道、具体源文件、具体测试文件与可复现的用户动作。监工按实际文件集合判断冲突，
   不能仅以泳道名判断。例如书架改 `TextbookShelf.kt`、原文改 `TextbookReader.kt`、提问改
   `BookPassageAsk.kt`、练习改 `TextbookPractice.kt` 可以并行；都要改 `TextbookWorkspace.kt` 则串行。
2. 现有参数足够时，不把每个界面细节改动带回路由文件。确需增加跨页契约时，先明确谁占用外壳。
3. Windows 全量回归：在仓库根目录运行 `tools/dev/heavy ./gradlew :desktopApp:test`。
   默认在 winbox 的交互桌面执行；核对返回的 JUnit 用例数、失败数和跳过数，并保留原有测试。
4. 界面变更还需用隔离书库走实际客户端路径。结构拆分的对照路径是书架 → 阅读 → 选段就问，
   比较同一材料、窗口尺寸和状态的截图。合成材料截图与真实教材证据分开；教材原文不得进 Git、评论或附件。
