# TON-1293：每日复习提醒

2026-11-06：代码、单测及 APK 构建已验证；模拟器第一道门尚未通过，不能据此宣称可用或合并上线。

## 行为

安卓「设置」提供每日提醒开关和 24 小时时间选择，默认关闭、19:00。首次开启申请通知权限；拒绝后保留原因说明和系统通知设置入口，后续切换不再弹权限框。关闭同时撤销闹钟和已显示通知。

闹钟触发后，在五秒内尝试同步账本；离线使用同一个 `coach-client.db` 副本。通知数直接取 `CourseView.dueReviews(now).size`，不实现第二套间隔算法。这里的数量沿用复习队列的知识点数，文案按卡要求显示「今天有 N 道到期」。零到期时不发通知。点击通知通过显式 PendingIntent 打开复习页，并保留隔离测试服务器地址。

用 `AlarmManager.setAndAllowWhileIdle` 调度下一次手机本地时间；每次投递、手机启动、升级安装、手动改时钟或时区时重算。偏好持久化在 SharedPreferences，普通进程回收不取消系统闹钟。日期计算使用 `java.time`，覆盖跨年、夏令时缺失和重复小时；到期日界线仍完全使用课程时区与凌晨四点规则。

按 Android 的[闹钟文档](https://developer.android.com/develop/background-work/services/alarms)，这是非精确提醒，省电或系统调度可能延后，不保证分钟级准点。不申请精确闹钟特殊权限。系统「强行停止」会阻止后台执行，必须重新打开应用；不能将普通进程回收的保证扩大为强行停止。通知权限遵循[运行时权限文档](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。

## 已运行的验证

通过 `tools/dev/heavy` 执行（JDK 17，SDK 使用本机配置）：

```sh
tools/dev/heavy ./gradlew :androidApp:testDebugUnitTest :shared:client:jvmTest \
  :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :server:app:installDist \
  -PcoachApplicationId=com.coach.android.ton1293 -Pcoach.port=18893 --console=plain
```

退出码 0。调度时间 8 项、shared/client 47 项，合计 55 项、0 失败、0 跳过。其中 CourseView 12 项，新增零到期与课程日界线覆盖。设备测试包仅构建通过，其运行结果尚未验证。

`tools/dev/heavy ./gradlew :desktopApp:compileKotlin --console=plain` 退出码 0，验证 shared/ui 新增默认设置插槽对桌面调用的兼容性。`python3 -m py_compile tools/dev/check-reminder.py`、`git diff --check` 退出码 0。隐私扫描没有新增命中；全库已有测试占位地址与规则命令命中，未改范围外文件。

## 第一门运行入口与阻塞

```sh
tools/dev/heavy --emulator python3 tools/dev/check-reminder.py
```

本轮命令停在取锁，持有者为 TON-1247 的 `ton1247-hold.sh`；未获得锁，未启动脚本、测试服务器或模拟器。完成独立工作后仅终止本轮取锁进程，退出码 143，未触碰持锁者。没有通知截图、点击后截图或重启后的 dumpsys 证据。不能把脚本存在当作验收通过。

脚本输入为本机配置 `coach.sdkDir` / `coach.tailHost` / `coach.course`、已构建的两个后缀 APK 和服务器 installDist，以及只读模式打开的 `~/Coach/data/coach.sqlite`。输出在被 gitignore 排除、权限 0700 的 `.reminder-evidence/`。重跑前先明确保存或删除旧目录，脚本拒绝覆盖。生产数据库通过 SQLite backup 复制，只在副本清除在途 jobs、turn_leases 与 pairing_code；测试使用新设备凭据和随机独立端口，桥指向关闭的本机端口 1，不请求模型、不接触生产进程。

固定 AVD `coach_1128_c70_r`，只读实例、独占 serial `emulator-5594`，包名 `com.coach.android.ton1293`。依次验证首次拒绝不重弹、两分钟后真实闹钟、回收进程后投递、数量一致、点击复习页、重启后闹钟仍在、关闭撤销、离线零到期静默。真实通知最多等 240 秒，超时或任何断言失败即停，不伪造广播替代计时证据；零到期分支单独调用投递逻辑。正常结束或失败都在前台回收自己启动的服务器和模拟器。

成功时仅交付 `notification.png`、`review.png`、`alarm-after-boot.txt`、`reminder-log.txt`、`result.json`；禁止附私有账本、副本 token 或服务端原始日志。其余情况保留现场，说明失败点，同因两次失败就移交排错。

## 后续真机与发布卡

模拟器门通过后再交 QA-C（不可用时 Sage）独立复跑。保持 Draft，不能自行合并。OPPO 和生产实测交统一合并发布卡：在获租的测试机安装相同后缀包，对隔离端口执行首次授权/拒绝、时间修改、关屏与进程回收、真实通知数量和点击页面、重启重排、关闭静默；补测厂商省电延迟、系统通知频道关闭及切换时区。上线后由发布卡在真实数据中核对到期数与通知，不以本卡单测代替真机证据。
