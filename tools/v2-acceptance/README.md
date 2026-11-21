# LearningCoach 2.0 真窗口验收工具（TON-1785）

本目录只包含独立验收与测量工具，不修改客户端产品代码。支持在 `winbox` 上对 `1.1.1`、`proto/v2-reader`、`main` 及后续 W1-A/W1-B 候选版本一条命令跑通，并以非抢前台模式（`setAutoRequestFocus(false)` / `setFocusableWindowState(false)` + 隔离 `user.home` / `COACH_LIBRARY_DIR` / 本地回环端口 `127.0.0.1:0`）运行，与 TON-1690 多窗口并行兼容。

## 文件说明

- `ReaderAcceptanceHarness.java`：针对任意 `smoke-runtime` 编译运行，支持三个子命令：
  - `measure`：在 `ImageComposeScene 离屏`（`1920x1080 @ 1.0x`、`2880x1800 @ 1.5x`）与 `真 OS 窗口`（`ComposeWindow`）下抓取四种状态（纯读原页、纯读文字、伴读/并排伴读、目录展开）的 PNG 与 Compose 语义树 `.meta.json`。
  - `bugs`：在真 OS 窗口运行 A 卡发现的 5 项阻断缺陷回归检查（跳页同步、原页/文字切换不回弹、目录抽屉可用、选段提问无须长滚、重启不闪空书架且 0 点击续读）。
  - `login`：在真 OS 窗口运行登录 4 条验收（首屏即登录且无内部词、登录到书架 `<= 2` 次点击、断网重启 `0` 点击回上次页、服务端吊销 `<= 3s` 回登录页并给出人话提示）。
- `measure_reader.py`：结合 Compose 语义树节点矩形与 PNG 实际渲染位图非背景像素，输出视口占比、正文/书页矩形与墨迹像素占比、嵌套层数、抽屉遮挡像素，并显式标注 `real_os_window`（真 OS 窗口）或 `image_compose_scene_offscreen`（ImageComposeScene 离屏）。零外部强依赖（有 Pillow 时优先使用，无 Pillow 时自动回退 Python 标准库解码）。
- `Run-Acceptance.ps1`：winbox 一键运行脚本。从 SSH（Session 0）调用时自动切入 Session 1 交互控制台运行真窗口，输出汇总表与 JSON/截图证据。
- `test_measure_reader.py`：`measure_reader.py` 单元测试。

## 用法

在 `winbox` 上一键跑全套（测量 + 5 缺陷回归 + 登录 4 条）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\v2-acceptance\Run-Acceptance.ps1 -Suite all -OutDir D:\agent\evidence\2.0-W1-C\latest
```

只跑某一项：`-Suite measure`、`-Suite bugs` 或 `-Suite login`。

本地/Mac 运行测量器单元测试：

```sh
python3 -m unittest discover -s tools/v2-acceptance -p "test_*.py" -v
```
