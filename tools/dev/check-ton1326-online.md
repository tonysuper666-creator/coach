TON-1326 的真实隔离联网验收。使用 macOS 图形会话、已登录的订阅 CLI、JDK 和 Python；所有命令在前台收集结果。

输入是两个本分支正式 `packageDmg` 产物、独立桥 venv、一个不存在的证据目录。第一个 DMG 的打包课程号须为 `course_` 加12位十六进制（与生产身份形态一致），但49个知识点全部由脚本原创合成；第二个打包课程号固定为 `course_132600000003`。不要复制任何生产库、令牌、课程原文或 generated 目录。

```sh
tools/dev/heavy ./gradlew :desktopApp:test :desktopApp:packageDmg :server:app:installDist --offline --console=plain
cp desktopApp/build/compose/binaries/main/dmg/Coach-1.0.0.dmg ../Coach-1.0.0.ton1326.dmg
tools/dev/heavy ./gradlew :desktopApp:packageDmg -Pcoach.course=course_132600000003 --offline --console=plain
cp desktopApp/build/compose/binaries/main/dmg/Coach-1.0.0.dmg ../Coach-configured.ton1326.dmg
python3 -m venv ../.ton1326-venv
../.ton1326-venv/bin/python -m pip install -r tools/bridge/requirements.txt
python3 -m unittest discover -s tools/dev -p test_ton1326_online.py
tools/dev/heavy python3 tools/dev/check-ton1326-online.py --dmg ../Coach-1.0.0.ton1326.dmg --configured-dmg ../Coach-configured.ton1326.dmg --bridge-python ../.ton1326-venv/bin/python --evidence-dir ../ton1326-online-evidence
```

脚本只读挂载两个镜像，直接运行镜像内的 `Contents/MacOS/Coach`，不调用 `open`，不安装到 `/Applications`。建立权限0700的短路径私有根，所有服务数据、Java user.home、配置、token、客户端账本和桥工作目录均在其下。服务器、透明记录器和订阅桥使用独立回环端口，禁止18791、8791、8790；清除继承的服务环境变量与 API key，不替换订阅 CLI。桥继承当前用户已有的订阅登录授权。

六个阶段：无 `COACH_COURSE` 且无凭据的冷启动（真实401）；经服务器一次性配对码 API 配对，独立设备token以0600落盘；无课程覆盖启动，HTTP同步49点；显式覆盖到另一个3点课程；新打包配置启动并以同一客户端目录重开，两次均无课程覆盖；原包无课程覆盖，使用已有 `COACH_OPEN_KP`/`COACH_AUTOSTART` 钩子发起真实导师讲课。桌面当前通过token文件接入，配对步骤由脚本调用真实API，**没有声称桌面配对表单或真人双击/点按已经验收**。新配置持久化指新打包 `Coach.cfg` 重开后生效，不是运行中修改生产配置。

服务器备课目录保持空，客户端只为当前知识点提供一段原创合成加法材料，不触发题库生成/备课批处理。讲课必须实际经过服务器、订阅桥和CLI，并出现非空 `complete` 消息、成功TEACH调用、`explanation_delivered` 账本事件；不能用桩或预生成答案替代。

输出为 `result.json`（镜像/配置hash、课程身份与数量、响应正文、seq、清理exit）、`http.jsonl`（真实请求路径/状态，WebSocket101）、脱敏服务/桥/桌面日志及各阶段Coach窗口截图。请求头、配对码和token不记录。截图只包含合成内容。HTTP观察代理将普通HTTP连接设为close，WS升级后逐字节转发；这项连接策略与直接连接不同，独立测试验证它不会伪造响应。

任一阶段失败或讲课195秒未完成即停止，保存失败证据并清理本次子进程、挂载和私有数据。桌面普通阶段须exit0；讲课采集后及服务结束使用受控SIGTERM，退出码如实记录；子孙进程或挂载残留使验收失败。若资源守护不授构建道，保留脚本并交接阻塞，不改道数或锁。

这覆盖隔离Mac联网链路。模拟器、OPPO、生产及发布门另行验收；缺少这些证据时不能称上线或整卡完成。
