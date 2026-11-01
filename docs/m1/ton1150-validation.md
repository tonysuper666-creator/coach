TON-1150 候选实现与验证记录；未上线，OPPO 验收未完成。

## 改动与依据

答错后的 EXACT 判分现在从服务端已复核题库读取解析，结果卡直接显示所选项、正确项及现有解析；不增加判分模型调用。解析缺失时明确说明没有已复核解析，不猜测学习者的心理错因。重讲仅携带当前线程最后一组已判错题的题干与选项文本，要求先解释错误再换例；待答题仍不带答案，带错题的讲解不复用通用首讲缓存。

原两条 `review_constructed` 的桥日志均是 `cli_exit` / `Operation not permitted`，模型正文为零，并非模型 JSON 或解析器失败。沙箱此前固定放行 `~/Coach/bridge/work`，隔离桥使用别的工作目录就无法启动 Codex。候选版只放行当前实例实际的 `work`，路径按 SBPL 字符串转义；旁边的账本仍不可读。构答作业将 CLI 启动失败记为 `CLI_ERROR`，不再混记为 `MALFORMED`。

## 本机门禁

以下命令实际退出 0（重命令均经 `tools/dev/heavy`，nice 15，2 workers）：

```sh
tools/dev/heavy ./gradlew :server:core:test :server:ai:test :server:app:test :shared:ui:jvmTest :androidApp:assembleDebug -PcoachApplicationId=com.coach.android.ton1150 --max-workers=2
tools/dev/heavy ./gradlew :server:core:test :server:app:installDist -PcoachApplicationId=com.coach.android.ton1150 --max-workers=2
python tools/bridge/tests/test_sandbox_workdir.py
python tools/bridge/tests/fault_injection.py
```

Kotlin：core 19、AI 61、app 90、UI 54，共 224 项，失败/跳过均 0；最后一处提示词调整后另跑 core 19 项并重装隔离服务。SBPL 实测 1 项通过，桥故障注入 60/60。测试覆盖无解析时承认依据缺失、已判错选项的冻结映射、待答题不泄键、其他线程隔离、最新正确组清除旧错题上下文，以及 CLI 失败不误分类、不重试。

## 原始失败作业复跑

使用冻结生产只读副本和 TON-1141 保留的原始复核输入，隔离服务 18850、独立桥 8815，未连接生产 18791/8791/8790 或 DeepTutor。保留原失败记录，显式重置这两个作业复跑；每条本轮物理调用 1 次：

| 作业 | 原状态 / 错误 | 复跑状态 / 错误 |
|---|---|---|
| `01M69QJ18QFYR7TTDCKJYY573Y` | failed / MALFORMED | completed / null |
| `01M69QJ18RT23H2APZHST9JKGJ` | failed / MALFORMED | completed / null |

同一隔离位置 `codex login status`：旧沙箱退出 1，新沙箱退出 0。原两条复核在 `oppo-2` 隔离库均已完成；该轮没有有效真机学习样本。生产监听 PID 集合在复跑前后相同。

## 全对后的新后台场景与重讲内容

在另一个全新冻结副本（`supply-2`）走同一个公开 tutor 协议：先两错一对 → 请求换例重讲 → 再三题全对。一次真实重讲首段同时指出「保险公司为减少外部性而供给保险」「只有政府融资才导致效率失效」这两处所选答案的问题，说明风险厌恶/健康不确定性与竞争市场效率条件，再换成面包与突发手术的例子。执行者逐条阅读这一段，1/1 命中；这是服务端内容验证，非真机首字测量。

三题全对后新产生的构答作业如下；旧副本没有构答作业，新增失败 0，三项 attempts 均为 1，无失败重试，最后活动作业 0：

| 作业 | 类型 | 状态 | attempts / last_error |
|---|---|---|---|
| `01M69ZEPJ605V6W4R20QQ48R65` | gen_constructed | completed | 1 / null |
| `01M69ZF96TNBRFYJJMBQPSXCYK` | review_constructed | completed | 1 / null |
| `01M69ZF96T3GFXF1BJ8FJABBHX` | review_constructed | completed | 1 / null |

原始作业对照、该场景报告和截图通过任务附件交付；真实教材、账本与完整备课包不进 Git。所有自建服务/桥/模拟器均已前台收尾。

## 客户端与验收缺项

模拟器已完整提交一组三题，2 张错题卡自动显示现成解析，前台判分模型调用 0。模拟器首轮 SDK 路径错误、后续旧坐标脚本的滚动/选择问题均保留为未完成轮次，不充当学习时延样本。

OPPO 首次安装因系统确认页而超时，确认安装后已核对 APK SHA-256 为 `94aca75d13c3185c1e00850c8e2f9f38b88ad5351943f45cb74776951fd4cea0`。后续测试期间设备被实际使用，已停止所有真机操作。没有有效的 OPPO 五道题截图或五次重讲分布；不以模拟器结果代替该验收项。意外截到的个人界面已从本次证据中移除。

后续先确认 OPPO 空闲，再前台一轮补齐真实点选/提交、至少五道不同题结果卡，以及五次重讲客户端首字和逐条首段审阅；最后交 Scout 独立验收，再交 Claude 系复核。当前不能宣称整卡达到验收标准。

## 11-01 rebase 与模拟器补测

候选版已 rebase 到 main `2a37c68`（含 #28 的 `coachApplicationId` / `-Pcoach.port`），并 cherry-pick TON-1149 的 `325e51f`（卡包不得默认连生产端口；补丁 ID 与原提交相同，两边谁先合另一边 rebase 都不冲突）。门禁在最终 head 上重跑，退出 0：`tools/dev/heavy ./gradlew :server:core:test :server:ai:test :server:app:test :shared:ui:jvmTest :androidApp:assembleDebug -PcoachApplicationId=com.coach.android.ton1150 --max-workers=2`，Kotlin 230 项失败/跳过 0；`test_sandbox_workdir.py` 通过，`fault_injection.py` 60/60。

模拟器（隔离服务 18860、独立桥 8816，数据为生产库只读拷贝，未连接 18791/8791/8790/DeepTutor）：6 个知识点各完整提交 3 道错题，18 张结果卡均显示所选项、正确项与已复核解析；判分到结果卡期间新增模型调用 0。6 次换例重讲的客户端首字（`CoachTtft`，模拟器口径）：5.827、6.418、6.718、7.411、7.772、7.978 秒，中位 7.065，p90（最近秩，n=6）7.978。这是模拟器数字，且与旧 4.7 秒口径（先追问再等首字）不是同一触发，不得当作真机结果。包名为卡包而不带 `coachUrl` 启动，或 `coachUrl` 指向 18791，均直接抛出 `Refusing production default`。作业表前后一致：completed 26、failed 1（拷贝自带的历史行），无新增，活动 0。
