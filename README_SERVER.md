# TeleDrive 服务器部署指南

本项目已支持 **7×24 小时无头服务器部署**，内置轻量级 Web 管理面板（无需安装桌面环境），支持 Docker 一键部署或直接运行 JVM 发行包。

## 1. Docker Compose 一键部署（推荐）

1. 修改 [docker-compose.yml](docker-compose.yml) 中的 `TELEDRIVE_ADMIN_PASSWORD` 为强密码。
2. 构建并后台启动：
   ```bash
   docker compose up -d --build
   ```
3. 查看运行日志：
   ```bash
   docker compose logs -f
   ```
4. 浏览器打开 `http://<服务器IP>:8080`，输入管理密码后：
   - 配置 Telegram `API ID` / `API Hash`（可从 https://my.telegram.org 获取）；
   - 使用 **二维码扫码** 或 **手机号 + 验证码** 登录 Telegram（国内服务器如无法直连可展开「网络代理设置」配置 SOCKS5/HTTP 代理）；
   - 选择或新建 **TeleDrive 存储频道**；
   - 勾选需要持续监控的频道，设置「每次抓取历史消息上限」与「定时巡检间隔」，点击保存即可开始 7×24 小时自动抓取。

## 2. 裸机 / 命令行直接运行（Linux / Windows）

构建服务器发行包：
```bash
# Linux 服务器打包（带 linux_amd64_gnu_ssl3 原生库）
./gradlew :server:installDist -PtdlightNativesOs=linux

# Windows 服务器打包
.\gradlew.bat :server:installDist -PtdlightNativesOs=windows
```

启动服务：
```bash
# Linux
TELEDRIVE_ADMIN_PASSWORD=yourpassword TELEDRIVE_HOST=0.0.0.0 ./server/build/install/server/bin/server

# Windows PowerShell
$env:TELEDRIVE_ADMIN_PASSWORD="yourpassword"
$env:TELEDRIVE_HOST="0.0.0.0"
.\server\build\install\server\bin\server.bat
```

## 3. 环境变量说明

| 变量名 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `TELEDRIVE_PORT` | `8080` | Web 管理面板 HTTP 端口 |
| `TELEDRIVE_HOST` | 有密码 `0.0.0.0` / 无密码 `127.0.0.1` | 绑定监听 IP |
| `TELEDRIVE_ADMIN_PASSWORD` | 空 | Web 管理面板密码（配置后启用 Cookie 鉴权） |
| `TELEDRIVE_DATA_DIR` | `./data` | 持久化目录（TDLib 会话、数据库、密钥、配置） |
| `TELEDRIVE_DOWNLOAD_DIR` | `./downloads` | 开启「自动下载到本地」时的保存目录 |
| `TELEDRIVE_POLL_INTERVAL_MINUTES` | `30` | 定时增量巡检间隔（分钟），`0` 为关闭定时巡检 |
| `TELEDRIVE_SYNC_ON_START` | `true` | 启动连上 Telegram 后是否自动执行一次历史补抓 |
| `TELEDRIVE_API_ID` / `TELEDRIVE_API_HASH` | 空 | 可选预置 Telegram API 凭据 |
