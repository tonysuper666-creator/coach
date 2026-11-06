# TON-1293：每日复习提醒

2026-11-06（Expert 排错）：修正实际操作行缺少自身标签的问题，并消除系统按钮选择器对英文标点的依赖。完整模拟器第一门退出 0，六个设备测试阶段全部通过；201 项本地测试通过。PR 保持 Draft，交 QA-C 独立验收。

## 行为

复习页标题栏的「每日提醒」入口提供开关和 24 小时时间选择，默认关闭、19:00；入口显示已关闭、当前时间或通知未允许。今天、学习和聊天页不占额外一行。开关整行可点击，父行用 `clearAndSetSemantics` 暴露标签、Switch 角色和开关状态，避免标签与装饰开关成为分离节点；设备测试保留自身标签断言，并检查实际点击及状态持久化。首次开启申请通知权限；拒绝后保留原因说明和系统通知设置入口，后续切换不再弹权限框。关闭同时撤销闹钟和已显示通知，进入复习页也撤销旧通知。

接收器只在数据库事务中读取同一个 `coach-client.db` 副本，不创建 SyncEngine、不发起网络同步、不清空副本、不写投影缓存，因此不会与前台同步交错写入 epoch。按现有 projector 生成投影，遇到不支持的事件时采用已保存的服务端投影，与前台相同。通知数直接取 `CourseView.dueReviews(now).size`，不实现第二套间隔算法。文案改为「今天有 N 个知识点要复习」。零到期时不发通知。点击通知通过显式 PendingIntent 打开复习页，并保留隔离测试服务器地址。本机尚未同步到的其他设备变更，须下次打开应用同步后才能反映。

用 `AlarmManager.setAndAllowWhileIdle` 调度下一次手机本地时间；每次投递、手机启动、升级安装、手动改时钟或时区时重算。偏好持久化在 SharedPreferences，普通进程回收不取消系统闹钟。日期计算使用 `java.time`，覆盖跨年、夏令时缺失和重复小时；到期日界线仍完全使用课程时区与凌晨四点规则。

按 Android 的[闹钟文档](https://developer.android.com/develop/background-work/services/alarms)，这是非精确提醒，省电或系统调度可能延后，不保证分钟级准点。不申请精确闹钟特殊权限。系统「强行停止」会阻止后台执行，必须重新打开应用；不能将普通进程回收的保证扩大为强行停止。通知权限遵循[运行时权限文档](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。

## 已运行的验证

通过 `tools/dev/heavy` 执行（JDK 17，SDK 使用本机配置）：

```sh
tools/dev/heavy ./gradlew :shared:domain:jvmTest :shared:client:jvmTest :shared:ui:jvmTest \
  :androidApp:testDebugUnitTest :desktopApp:compileKotlin \
  -PcoachApplicationId=com.coach.android.ton1293 -Pcoach.port=18893 --console=plain

tools/dev/heavy ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest \
  :server:app:installDist -PcoachApplicationId=com.coach.android.ton1293 -Pcoach.port=18893 --console=plain

tools/dev/heavy ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest \
  :androidApp:testDebugUnitTest -PcoachApplicationId=com.coach.android.ton1293 -Pcoach.port=18893 --console=plain
```

本轮分别退出 0（36 / 44 / 15 秒）。domain 55 项、client 59 项、ui 79 项、安卓调度 8 项，合计 201 项、0 失败、0 跳过；第三条在产品语义修复后重建 APK/设备测试包并复跑安卓单测。覆盖零到期、课程日界线和调度跨年/时区/夏令时；桌面编译通过。`python3 -m py_compile tools/dev/check-reminder.py`、`git diff --check` 退出码 0。

## 无障碍定位与设备门

首次在 `b8f7309` 上仅加入诊断，完整设备命令退出 1（`prepare`：6.180 秒）。`rootInActiveWindow` 的首个 `isCheckable` 确为可见、可点击的操作行，不是选错了装饰节点：`class=android.view.View text=null description=null checkable=true clickable=true`；文字「每日到期提醒」位于子 TextView。原测试要求操作节点自身带标签，故保留断言并修正产品语义。

父操作行改用 `clearAndSetSemantics` 后，设备导出的同一节点为 `description=每日到期提醒 checkable=true clickable=true`，原标签断言通过。随后在查找英文 `Don't allow` 处超时（`prepare`：25.332 秒）。本轮成功运行的实际权限节点为 `com.android.permissioncontroller:id/permission_deny_button`、文字 `Don’t allow`（U+2019 弯引号），不匹配原 ASCII 引号选择器。测试改为按系统权限控制器资源 ID 选择实际拒绝按钮，开启 view ID 报告，并在超时时记录节点树；原生时间选择器确认按钮同样按 `android:id/button1` 定位。仍要求真实拒绝、系统权限为 DENIED、拒绝原因可见、再次切换不重复请求，以及开关实际改变持久化状态。

本轮实际执行下列组合命令，构建设备测试包成功（13 秒），完整脚本退出 0：

```sh
tools/dev/heavy --emulator bash -c './gradlew :androidApp:assembleDebugAndroidTest -PcoachApplicationId=com.coach.android.ton1293 -Pcoach.port=18893 --console=plain && python3 tools/dev/check-reminder.py'
```

| 设备阶段 | 项数 / 失败 | 秒 |
| --- | --- | --- |
| prepare | 1 / 0 | 6.640 |
| schedule | 1 / 0 | 2.486 |
| tap | 1 / 0 | 4.024 |
| timezone | 1 / 0 | 0.080 |
| restored | 1 / 0 | 0.038 |
| zero | 1 / 0 | 0.238 |

只读备份包含 736 条事件，隔离服务端端口 50589，`aiCallsAdded=0`。开启通知后设为两分钟后的时间并回收应用进程，等待真实 AlarmManager 投递，日志为 `due=9 notified`；通知与点开后的复习页均显示 9 个到期知识点。首次拒绝说明、后续不重弹、开关实际点击和持久化均通过。

系统命令改时区到 `America/New_York` 后，设备断言重排时间与该时区计算结果相等（`1794020340000`）。重启后 `dumpsys alarm` 保留本包的 `RTC_WAKEUP` / `REVIEW_REMINDER`，设备断言开关仍开启且调度在未来（`1794088740000`）；关闭后无本包活动闹钟并撤销通知。停掉隔离服务端、清空一次性本地副本后，`due=0 skipped`，关闭后的过期投递也保持静默。

三张截图已逐张查看，只有通知、复习知识点列表和权限拒绝说明，未打开题目或生成卷。脚本在系统重启前保存提醒日志，最终合并重启后的日志，保留权限、真实投递和时区证据。截图和脱敏后的设备记录通过任务附件交付，不进入仓库。

脚本输入为本机配置 `coach.sdkDir` / `coach.tailHost` / `coach.course`、已构建的两个后缀 APK 和服务器 installDist，以及只读模式打开的 `~/Coach/data/coach.sqlite`。输出在被 gitignore 排除、权限 0700 的 `.reminder-evidence/`。重跑前先明确保存或删除旧目录，脚本拒绝覆盖。生产数据库通过 SQLite backup 复制，只在副本清除在途 jobs、turn_leases 与 pairing_code；测试使用新设备凭据和随机独立端口，桥指向关闭的本机端口 1，不请求模型、不接触生产进程。

固定 AVD `coach_1128_c70_r`，只读实例、独占 serial `emulator-5594`，包名 `com.coach.android.ton1293`。依次验证首次拒绝不重弹与 Switch 标签、两分钟后真实闹钟、回收进程后投递、数量一致、点击复习页、用系统命令改时区后重排、重启后闹钟仍在、关闭撤销、离线零到期静默。真实通知最多等 240 秒，超时或任何断言失败即停，不伪造广播替代计时证据；零到期分支单独调用投递逻辑。正常结束或失败都在前台回收自己启动的服务器和模拟器。

成功时交付 `notification.png`、`review.png`、`permission-denied.png`、`alarm-after-boot.txt`、`reminder-log.txt`、`result.json` 及仅含提醒节点和阶段结果的诊断记录；禁止附私有账本、副本 token 或服务端原始日志。其余情况保留现场，说明失败点，同因两次失败就移交排错。手动把系统时间往回拨，仍可能在同一自然日再次提醒，此建议项尚未实现去重。

## 后续真机与发布卡

模拟器门通过后再交 QA-C（不可用时 Sage）独立复跑。保持 Draft，不能自行合并。OPPO 和生产实测交统一合并发布卡：在获租的测试机安装相同后缀包，对隔离端口执行首次授权/拒绝、时间修改、关屏与进程回收、真实通知数量和点击页面、重启重排、关闭静默；补测厂商省电延迟、系统通知频道关闭及切换时区。上线后由发布卡在真实数据中核对到期数与通知，不以本卡单测代替真机证据。
