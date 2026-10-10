# 一键部署说明（云服务器）

本文对应脚本：[`deploy-oneclick.sh`](./deploy-oneclick.sh)

## 适用环境

- Ubuntu / Debian（使用 `apt-get`）
- 已将本项目上传到服务器
- 使用 Nginx + systemd 部署

## 0. 上传项目

把整个 `ai-project` 目录上传到服务器，例如：

```bash
scp -r ./ai-project root@你的服务器IP:/root/
```

## 1. 首次执行（生成环境变量模板）

进入项目目录后执行：

```bash
cd /root/ai-project
chmod +x ./deploy-oneclick.sh
sudo ./deploy-oneclick.sh --install-deps --domain your-domain.com
```

> 首次运行会生成环境变量文件并**主动退出**，这是正常行为。

默认生成路径：

- `/etc/my-ai/my-ai.env`

## 2. 修改环境变量（必须）

编辑文件：

```bash
sudo nano /etc/my-ai/my-ai.env
```

至少修改这些值：

- `JWT_SECRET`（至少 32 字节）
- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `CORS_ALLOWED_ORIGINS`（改成你的域名）
- `LLM_API_KEY`（OpenAI 兼容模型 API Key，按你实际使用）

## 3. 第二次执行（正式部署）

```bash
cd /root/ai-project
sudo ./deploy-oneclick.sh --domain your-domain.com
```

完成后会自动：

- 构建后端并复制到 `/opt/my-ai/backend/app.jar`
- 构建前端并发布到 `/var/www/my-ai`
- 写入 systemd 服务：`/etc/systemd/system/my-ai.service`
- 写入 Nginx 配置并重启服务

## 常用命令

查看后端状态：

```bash
systemctl status my-ai --no-pager
```

查看后端日志：

```bash
journalctl -u my-ai -n 100 --no-pager
```

检查 Nginx：

```bash
nginx -t
```

## 可选参数

```bash
sudo ./deploy-oneclick.sh [选项]
```

- `--install-deps`：自动安装 Java17、Nginx、Node.js 20
- `--domain <domain>`：Nginx `server_name`，默认 `_`
- `--backend-port <port>`：后端端口，默认 `8081`
- `--api-base-url <url>`：前端 API 基地址，默认自动使用 `http://<domain>`
- `--env-file <path>`：后端环境变量文件路径，默认 `/etc/my-ai/my-ai.env`
- `-h, --help`：帮助

## 注意事项

- 这是 HTTP 部署流程；生产建议后续再加 HTTPS（Certbot）。
- 脚本会覆盖当前站点对应的 Nginx 配置与前端发布目录内容。
- 若后端数据库不在本机，请确保安全组/防火墙放行数据库端口。
- 若你不传 `--domain`，必须手动传 `--api-base-url`，否则脚本会报错并停止。
