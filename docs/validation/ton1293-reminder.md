# TON-1293：每日复习提醒

2026-11-06（解锁后续跑）：模拟器门实际运行两次，均退出 1，停在 Switch 无障碍标签断言。通知等后续路径未执行；不能宣称可用或合并上线。已停止同因重试，交 Expert 排错。

## 行为

复习页标题栏的「每日提醒」入口提供开关和 24 小时时间选择，默认关闭、19:00；入口显示已关闭、当前时间或通知未允许。今天、学习和聊天页不占额外一行。开关整行可点击，使用 Switch 角色；设备测试尚不能证明无障碍标签正确，须修复。首次开启申请通知权限；拒绝后保留原因说明和系统通知设置入口，后续切换不再弹权限框。关闭同时撤销闹钟和已显示通知，进入复习页也撤销旧通知。

接收器只在数据库事务中读取同一个 `coach-client.db` 副本，不创建 SyncEngine、不发起网络同步、不清空副本、不写投影缓存，因此不会与前台同步交错写入 epoch。按现有 projector 生成投影，遇到不支持的事件时采用已保存的服务端投影，与前台相同。通知数直接取 `CourseView.dueReviews(now).size`，不实现第二套间隔算法。文案改为「今天有 N 个知识点要复习」。零到期时不发通知。点击通知通过显式 PendingIntent 打开复习页，并保留隔离测试服务器地址。本机尚未同步到的其他设备变更，须下次打开应用同步后才能反映。

用 `AlarmManager.setAndAllowWhileIdle` 调度下一次手机本地时间；每次投递、手机启动、升级安装、手动改时钟或时区时重算。偏好持久化在 SharedPreferences，普通进程回收不取消系统闹钟。日期计算使用 `java.time`，覆盖跨年、夏令时缺失和重复小时；到期日界线仍完全使用课程时区与凌晨四点规则。

按 Android 的[闹钟文档](https://developer.android.com/develop/background-work/services/alarms)，这是非精确提醒，省电或系统调度可能延后，不保证分钟级准点。不申请精确闹钟特殊权限。系统「强行停止」会阻止后台执行，必须重新打开应用；不能将普通进程回收的保证扩大为强行停止。通知权限遵循[运行时权限文档](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。

## 已运行的验证

通过 `tools/dev/heavy` 执行（JDK 17，SDK 使用本机配置）：

```sh
tools/dev/heavy ./gradlew :shared:domain:jvmTest :shared:client:jvmTest :shared:ui:jvmTest :androidApp:testDebugUnitTest \
  :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :server:app:installDist \
  :desktopApp:compileKotlin -PcoachApplicationId=com.coach.android.ton1293 -Pcoach.port=18893 --console=plain
```

2026-11-06 复核修订后退出码 0（58 秒）：domain 55 项、client 59 项、ui 79 项、安卓调度 8 项，合计 201 项、0 失败、0 跳过。覆盖零到期、课程日界线和调度跨年/时区/夏令时。设备测试包构建通过。随后补入「今天/学习页不显示入口、复习页显示入口」的集成断言，重跑 `tools/dev/heavy ./gradlew :shared:ui:jvmTest --console=plain`，退出码 0（18 秒），79 项通过。

上述命令同时验证桌面编译和 APK/服务器构建。`python3 -m py_compile tools/dev/check-reminder.py`、`git diff --check` 退出码 0。

## 第一门实际结果与排错交接

```sh
tools/dev/heavy --emulator python3 tools/dev/check-reminder.py
```

20:57Z 收到解锁通知后执行上述命令，取得模拟器道，启动了隔离服务器和指定 AVD。第一轮退出 1：`prepare` 的 `Switch must expose its label` 断言失败（设备测试 1 项、1 失败，10.936 秒）。已经通过配对、读取到期数、今天页无提醒入口等前置步骤，但尚未点击权限请求，不能算权限拒绝验收通过。

尝试给父 Row 显式设置 `contentDescription = "每日到期提醒"`，内部 Switch 用 `clearAndSetSemantics {}` 消除重复节点。通过 `tools/dev/heavy ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest -PcoachApplicationId=com.coach.android.ton1293 -Pcoach.port=18893 --console=plain` 重建，退出 0（27 秒）。再次执行完整设备门，排队后进入模拟器，同一断言仍失败，退出 1（设备测试 1 项、1 失败，8.801 秒）。原断言未删除、跳过或放宽。

按同因两次失败停止重试。未奏效的产品补丁已回退，单独保存为 `ton1293-attempted-label.diff` 供排错参考；PR 产品代码仍是 `ecef5bae9c0d` 的版本。第一、二轮的 `prepare.txt` 分别保存在私有 `.reminder-evidence-attempt1/`、`.reminder-evidence/`，均可交付无内容堆栈。脚本 finally 已回收两轮自己启动的服务器和模拟器；结束后 `adb devices` 无 `emulator-5594`，没有持锁等待者残留。生产和其他持锁者未改动。

交 Expert：先采集 `rootInActiveWindow` 节点树，确认 `waitNode { it.isCheckable }` 实际选中的节点，区分产品语义缺陷与选择器错误；修正后保持“真实 Switch 有标签且可开关”的验收强度。随后在同一隔离条件跑完整脚本，输出三张截图、闹钟、时区、重启和零到期证据；未覆盖情况停止并写明，禁止改弱断言冒充通过。当前到点通知、零到期静默、重启 dumpsys、实际改时区和权限拒绝五项均未验证，无成功设备截图。

脚本输入为本机配置 `coach.sdkDir` / `coach.tailHost` / `coach.course`、已构建的两个后缀 APK 和服务器 installDist，以及只读模式打开的 `~/Coach/data/coach.sqlite`。输出在被 gitignore 排除、权限 0700 的 `.reminder-evidence/`。重跑前先明确保存或删除旧目录，脚本拒绝覆盖。生产数据库通过 SQLite backup 复制，只在副本清除在途 jobs、turn_leases 与 pairing_code；测试使用新设备凭据和随机独立端口，桥指向关闭的本机端口 1，不请求模型、不接触生产进程。

固定 AVD `coach_1128_c70_r`，只读实例、独占 serial `emulator-5594`，包名 `com.coach.android.ton1293`。依次验证首次拒绝不重弹与 Switch 标签、两分钟后真实闹钟、回收进程后投递、数量一致、点击复习页、用系统命令改时区后重排、重启后闹钟仍在、关闭撤销、离线零到期静默。真实通知最多等 240 秒，超时或任何断言失败即停，不伪造广播替代计时证据；零到期分支单独调用投递逻辑。正常结束或失败都在前台回收自己启动的服务器和模拟器。

成功时仅交付 `notification.png`、`review.png`、`permission-denied.png`、`alarm-after-boot.txt`、`reminder-log.txt`、`result.json`；禁止附私有账本、副本 token 或服务端原始日志。其余情况保留现场，说明失败点，同因两次失败就移交排错。手动把系统时间往回拨，仍可能在同一自然日再次提醒，此建议项尚未实现去重。

## 后续真机与发布卡

模拟器门通过后再交 QA-C（不可用时 Sage）独立复跑。保持 Draft，不能自行合并。OPPO 和生产实测交统一合并发布卡：在获租的测试机安装相同后缀包，对隔离端口执行首次授权/拒绝、时间修改、关屏与进程回收、真实通知数量和点击页面、重启重排、关闭静默；补测厂商省电延迟、系统通知频道关闭及切换时区。上线后由发布卡在真实数据中核对到期数与通知，不以本卡单测代替真机证据。
