# TON-1889 书的地图（Book Map）隔离复走与抽点工具

为 winbox 验收准备隔离的 10 本真实书书库、隔离后端连接和启动脚本，不碰生产端口（`18791`/`8790`/`8791`）和正式书库（`~/Coach/books`）。输出 JSON 只含计数与坐标，不含书的原文或完整路径。

## 1. 构建与数据

- **构建**：直接在本 PR（PR #666）检出运行 `gradlew.bat :desktopApp:run`（由脚本生成 `launch-window.ps1` 拉起）与 `:server:app:installDist`。
- **数据**：把 TON-1875 的 10 本真实书路径写进检出外的 `books.txt`（UTF-8 无 BOM，一行一路径，`#` 开头为注释，与 `tools/reader-v2/structure-audit` 相同）。

## 2. 一键准备（二选一，按 winbox 是否有桥决定）

### 路径 A：有可用的隔离 Bridge（推荐，真实 Haiku 5.5 Batch）

在非生产端口（如 `18892`）连着 `tools/bridge` 时执行：

```powershell
powershell -ExecutionPolicy Bypass -File tools\reader-v2\book-map-verify\prepare-winbox.ps1 `
  -BooksFile D:\agent\_trash\structure-audit-1875\books.txt `
  -WorkDir D:\agent\_trash\book-map-1889 `
  -ServerPort 18889 `
  -BridgeUrl http://127.0.0.1:18892
```

脚本会自动：
1. 在 `127.0.0.1:18889` 启动隔离 `server/app`（数据在 `D:\agent\_trash\book-map-1889\server-data`）；
2. 按 Windows 正式导入路径导入 10 本书进 `D:\agent\_trash\book-map-1889\books\library.sqlite`，并将每本书默认学法设为 `BOOK_MAP`；
3. 调用 `/api/books/{id}/book-map/batch`（`formatVersion=2`，Haiku 5.5 Batch）为每本书生成并落盘 `book_what_about_maps`；
4. 生成 `launch-window.ps1` 和 `stop-server.ps1`。

### 路径 B：winbox 无本地 Bridge，连远端隔离/测试服务

如果 winbox 没有本机 API key / Bridge，可直接指向可访问的非生产测试服务（或加 `-SkipGenerate` 只先导入 10 本书，进窗口后用 Sable 登录再点「生成这本书的地图」）：

```powershell
powershell -ExecutionPolicy Bypass -File tools\reader-v2\book-map-verify\prepare-winbox.ps1 `
  -BooksFile D:\agent\_trash\structure-audit-1875\books.txt `
  -WorkDir D:\agent\_trash\book-map-1889 `
  -RemoteServerUrl https://<隔离测试服务地址> `
  -SkipGenerate
```

## 3. 真窗口复走与抽点

1. 拉起隔离窗口：
   ```powershell
   powershell -ExecutionPolicy Bypass -File D:\agent\_trash\book-map-1889\launch-window.ps1
   ```
2. 逐本打开 10 本书，切到「先摸全貌」查看「这本书讲什么」地图，每本随机点 10 个要点核对跳页与高亮原句；再点目录顶部「不对？改」改一次章名 / 拆并 / 正文起始页，确认地图立即重排且关窗重开仍在。
3. 可选运行自动坐标核对（只输出计数与坐标，不含原文）：
   ```powershell
   .\gradlew.bat :desktopApp:bookMapVerify --args="sample-audit --library D:\agent\_trash\book-map-1889\books --out D:\agent\_trash\book-map-1889\sample-report.json" --no-daemon
   ```
4. 验收完清理隔离服务端：
   ```powershell
   powershell -ExecutionPolicy Bypass -File D:\agent\_trash\book-map-1889\stop-server.ps1
   ```

## 4. 自检（无真实书）

```powershell
.\gradlew.bat :desktopApp:test --tests com.coach.desktop.books.BookMapVerifyTest --no-daemon
```
