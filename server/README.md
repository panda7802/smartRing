# smartRing server

基于 Flask 的用户认证、赞念同步 JSON API 和管理后台，使用 SQLite 存储用户、登录会话与用户每日赞念数，生产环境由 Gunicorn 托管。

## 本地运行

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python app.py
```

可用环境变量：

- `HOST`：监听地址，默认 `127.0.0.1`
- `PORT`：监听端口，默认 `8001`
- `DATABASE_PATH`：SQLite 文件路径，默认 `server/data/smartring.db`
- `APP_APK_PATH`：Android APK 文件路径，默认 `server/app/sr.apk`
- `LOG_LEVEL`：日志级别，默认 `INFO`

## 测试

在 `server` 目录执行：

```bash
python -m unittest discover -s tests -v
```

## 生产运行

Gunicorn 的应用入口为 `wsgi:app`：

```bash
gunicorn --workers 2 --threads 4 --bind 127.0.0.1:8001 wsgi:app
```

仓库中的 `deployment/smartring.service` 会使用 `/opt/smartRing/venv` 虚拟环境启动该命令。Nginx 继续把 `/smartRing/` 转发到本服务，站点首页的兜兜游戏盒代理配置不需要修改。

Android 安装包下载地址：`https://www.panzhenghao.cn/smartRing/app/sr.apk`。生产文件位于 `/opt/smartRing/downloads/sr.apk`，Nginx 直接提供下载，Flask 同时保留同路径的兼容路由。

正式接口详见 [API.md](API.md)。

## 管理后台

后台入口：`https://www.panzhenghao.cn/smartRing/admin/`

登录后显示用户 ID、用户名和注册时间；每个用户后面提供“查看赞念日历”入口，可按月查看该用户每天的赞念数并切换上月、下月。顶部“查看祈福记录”入口会显示每次 NFC 祈福的发送方、接收方、昵称、祝福语、贴纸 UUID、APP 包名、是否自祈福和接收时间，每页 100 条。管理员账号与普通用户分表保存，后台使用独立的 12 小时安全会话。

创建或更新管理员：

```bash
python3 app.py create-admin --name <管理员用户名>
```

命令会交互式读取密码，不会把明文密码写入命令历史或项目配置。
