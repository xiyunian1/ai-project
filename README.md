# AI Project

一个前后端分离的 WMS 智能助手项目：
- `ai-frontend`: Vue 3 + Vite + Electron 前端
- `my-ai`: Spring Boot 后端（认证、聊天、库存工具接口）

## 快速启动

### 1) 启动后端
在 `my-ai` 目录执行：

```powershell
$env:JWT_SECRET='replace-with-at-least-32-bytes-secret'
$env:DIFY_API_KEY='your-dify-key'
.\mvnw.cmd spring-boot:run
```

默认地址：`http://localhost:8081`

### 2) 启动前端
在 `ai-frontend` 目录执行：

```powershell
npm install
npm run dev -- --host 127.0.0.1 --port 5173
```

访问：`http://127.0.0.1:5173`

## 关键环境变量

后端（`my-ai`）：
- `JWT_SECRET`（必填）：JWT 签名密钥，至少 32 字节
- `DIFY_API_KEY`：Dify API Key
- `DIFY_BASE_URL`：默认 `http://localhost:8080/v1`
- `DIFY_MOCK_ENABLED`：默认 `true`，生产建议 `false`
- `DIFY_REQUEST_TIMEOUT_SECONDS`：请求超时秒数，默认 `20`
- `DB_URL/DB_USERNAME/DB_PASSWORD`：数据库配置（默认 H2 内存库）

前端（`ai-frontend`）：
- `VITE_API_BASE_URL`：后端地址，默认 `http://localhost:8081`

## 已实现能力

- 用户注册/登录/改密（JWT）
- 登录态路由保护
- 聊天文本流式返回（SSE over fetch + Authorization 头）
- 图片问答
- 多会话本地存储
- 库存洞察页（低库存 + 按商品查询）
- 流程助手页（流程规范与异常清单）

## 测试与构建

后端测试：
```powershell
cd my-ai
.\mvnw.cmd test
```

前端构建：
```powershell
cd ai-frontend
npm run build
```
