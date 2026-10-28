# 局域网每日图源服务

给 Aerial 的自定义「每日图源」用的最小服务：每天从本机相册挑一张横图，生成 Aerial 认识的
`daily.json` 和图片，通过局域网 HTTP 提供。Aerial 默认仍用 Bing；在电视或手机设置里填上
这个服务的地址、端口和可选 Token 后才会切换过来。

服务只做两件事：

- `nginx`：提供 `/health`、`/daily.json`、`/img/*.jpg`
- `gen`：每天 `GEN_HOUR` 点从相册选一张图，裁成 1920×1080（可选 4K），保留最近 `DAYS` 天

## 前置

- 一台常开的机器（NAS、小主机、树莓派等），装好 Docker 和 Docker Compose v2
- 相册目录里有 JPEG；服务只选**横图、宽 ≥1920** 的照片
- 电视和这台机器在同一局域网

## 启动

```bash
cd tools/daily-source
cp .env.example .env
# 编辑 .env：至少设置 PHOTOS_PATH；建议设置 AERIAL_TOKEN
mkdir -p data/web data/state
docker compose up -d --build
```

`.env` 常用项：

| 变量 | 说明 |
|---|---|
| `PHOTOS_PATH` | 宿主机相册目录的**绝对路径**，必填 |
| `AERIAL_PORT` | 对电视提供服务的端口，默认 8899，可改 |
| `AERIAL_TOKEN` | 可选共享密钥；留空则不校验。生成：`openssl rand -hex 16` |
| `TZ` | 时区，默认 `Asia/Shanghai` |
| `GEN_HOUR` | 每天几点换图，默认 4 |
| `DAYS` | 保留最近几天，默认 8（Aerial 一次读 8 条） |
| `UHD` | 是否同时生成 `_UHD.jpg`，默认 1 |

> `PHOTOS_PATH` 是 Docker 宿主机的路径；Docker Desktop 需要先把该目录加入文件共享。

启动日志：

```bash
docker compose logs -f gen
docker compose logs -f web
```

首次启动会回填最近 `DAYS` 天；之后每天 `GEN_HOUR` 追加当天的一张，并清理窗口外的旧图。

## 验证

```bash
# 下面以默认端口 8899 为例；改过 AERIAL_PORT 时换成对应端口
# 有 token 时：
TOKEN=$(grep '^AERIAL_TOKEN=' .env | cut -d= -f2-)
curl -H "X-Aerial-Token: $TOKEN" http://<主机IP>:8899/health
curl -H "X-Aerial-Token: $TOKEN" http://<主机IP>:8899/daily.json
curl -o /tmp/photo.jpg http://<主机IP>:8899/img/$(date +%Y%m%d).jpg

# token 留空时去掉 -H 参数
```

`/health` 应返回 `{"service":"aerial-daily","v":1}`，`daily.json` 里应有最多 `DAYS` 条
`startdate/url/urlbase/title/copyright`。

## 在 Aerial 里设置

- TV：个性化 → 每日图源 → 填地址和端口（Token 可选）→「探测局域网」或「保存」
- 手机：个性化面板右侧扫码 → 每日图源 → 同样填写 → 保存/探测

地址填 IP（如 `192.168.1.10`）或 mDNS 名（如 `nas.local`）；**端口要和 `AERIAL_PORT` 一致**。
Token 要和 `AERIAL_TOKEN` 一致；服务端留空时 App 里也留空。

## 安全

- 只用于局域网；不要把 `AERIAL_PORT` 暴露到公网，也不要在路由器上做端口转发
- 服务是明文 HTTP；Token 只是防止同一局域网内的误接入/伪造，不是强认证
- Aerial 端只接受私有 IPv4 或 `.local` 地址，并先请求 `/health` 校验 magic
- 建议在路由器给这台机器保留 DHCP 地址，避免 IP 变化

## 排错

- `gen` 日志出现 `no photo available`：检查 `PHOTOS_PATH`、扩展名（JPEG）和横图宽度
- App 保存失败：确认端口一致、Token 一致、电视能访问 `http://<主机IP>:<端口>/health`
- `.local` 解析不了：改用 IP，或在路由器配置本地 DNS
- nginx 403：Token 不一致；先用 `curl` 带 header 验证
- 换相册/天数：改 `.env` 后 `docker compose up -d`（改生成器代码才需要 `--build`）
- 备份：备份 `data/`、`.env`；更新镜像后 `docker compose up -d --build`

## 更新/卸载

```bash
docker compose pull          # 更新 nginx
docker compose up -d --build # 重建生成器
docker compose down          # 停止（保留 data/）
# 彻底删除：docker compose down -v --rmi local 后删 data/ 和 .env
```
