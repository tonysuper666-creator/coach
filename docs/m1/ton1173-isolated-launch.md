# TON-1173 隔离图标启动

使用本卡构建，指定独立包名与隔离服务端口（示例 18873）：

```sh
tools/dev/heavy ./gradlew :androidApp:assembleDebug -PcoachApplicationId=com.coach.android.ton1173 -Pcoach.port=18873 -Pcoach.isolatedLaunchPort=18873 --offline
```

`coach.tailHost` 沿用本机 `~/Coach/config/local.properties` 中的允许主机。构建前应由测试负责人确认示例端口对应本卡隔离实例、数据与合成测试服务；本构建不启动任何服务。安装及普通桌面图标启动仅在满足本卡手机租约和保护条件后进行，无需传 `coachUrl`。隔离实例未准备好时不要开始测试。卡结束卸载分卡包，测试包 `.test` 用完即卸。

仅 debug 分卡包允许此端点；缺少 `coach.isolatedLaunchPort`、无效端口或端口 18791、8791、8790 均拒绝连接。显式传入无效 `coachUrl` 时不回退到图标配置。正式包维持原有默认行为。桌面启动实际点击次数仍需真机验证。
