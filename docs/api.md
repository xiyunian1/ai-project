# API 接口说明

## 通用约定

- 后端默认地址：`http://localhost:8081`
- 受保护接口需要请求头：`Authorization: Bearer <token>`
- 常见响应结构包含 `code` 和 `message` 字段；部分工具接口直接返回数组或业务对象。

## 认证接口

### 登录

- 方法：`POST`
- 路径：`/api/auth/login`
- 鉴权：不需要
- 请求体：

```json
{
  "username": "admin",
  "password": "password"
}
```

- 成功响应：返回 JWT、过期时间、显示名称和账号名。

### 注册

- 方法：`POST`
- 路径：`/api/auth/register`
- 鉴权：不需要
- 请求体：

```json
{
  "username": "student",
  "password": "password123",
  "displayName": "学生用户"
}
```

- 成功响应：`201 Created`，返回注册成功信息。

### 修改密码

- 方法：`POST`
- 路径：`/api/auth/change-password`
- 鉴权：需要
- 请求体：

```json
{
  "currentPassword": "old-password",
  "newPassword": "new-password"
}
```

- 成功响应：返回密码修改成功信息。

## 库存工具接口

### 按商品名称查询库存

- 方法：`GET`
- 路径：`/api/tools/query_stock`
- 鉴权：需要
- 查询参数：`productName`
- 示例：`/api/tools/query_stock?productName=苹果`
- 成功响应：返回匹配的库存记录列表。

### 低库存预警

- 方法：`GET`
- 路径：`/api/tools/low_stock`
- 鉴权：需要
- 成功响应：返回低库存商品数组。

## 智能问答接口

### 普通问答

- 方法：`GET`
- 路径：`/api/ai/chat`
- 鉴权：需要
- 查询参数：`query`
- 说明：阻塞式返回 AI 回复文本。

### 流式问答

- 方法：`GET`
- 路径：`/api/ai/chat/stream`
- 鉴权：需要
- 查询参数：`query`
- 响应类型：`text/event-stream`
- 说明：用于前端聊天页流式渲染。

### 图片问答

- 方法：`POST`
- 路径：`/api/ai/chat/image`
- 鉴权：需要
- 请求类型：`multipart/form-data`
- 字段：`image`、`query`
- 说明：上传图片并附带可选问题。

## 数据分析接口

### SQL 问答

- 方法：`GET`
- 路径：`/api/ai/chat/sql`
- 鉴权：需要
- 查询参数：`query`
- 成功响应：返回 `answer`、`sql`、`rowCount` 和 `rows`。

### 阻塞式分析

- 方法：`GET`
- 路径：`/api/ai/chat/analyze`
- 鉴权：需要
- 查询参数：`query`
- 成功响应：返回分析结论、SQL 模板和查询结果预览。

### 流式分析

- 方法：`POST`
- 路径：`/api/ai/chat/analyze/stream`
- 鉴权：需要
- 响应类型：`text/event-stream`
- 请求体：

```json
{
  "query": "分析当前低库存风险",
  "history": [
    {
      "role": "user",
      "content": "上一轮问题"
    }
  ],
  "dbConfig": null,
  "modelConfig": {
    "enabled": true,
    "provider": "openai-compatible",
    "nl2sqlProvider": "openai-compatible",
    "baseUrl": "https://api.deepseek.com",
    "apiKey": "sk-xxx",
    "model": "deepseek-chat"
  }
}
```

- 说明：分析页使用该接口做连续追问和 SQL 明细展示。`dbConfig` 和 `modelConfig` 都是可选字段；未传或未启用时，后端使用环境变量中的默认配置。

### 获取可用模型

- 方法：`POST`
- 路径：`/api/ai/models`
- 鉴权：需要
- 请求体：

```json
{
  "baseUrl": "https://api.deepseek.com",
  "apiKey": "sk-xxx"
}
```

- 成功响应：

```json
{
  "code": 200,
  "message": "Models loaded",
  "models": ["deepseek-chat", "deepseek-reasoner"]
}
```

- 说明：系统配置页使用该接口通过后端代理请求 OpenAI 兼容服务的 `/models`，避免前端跨域问题，并将返回的模型 ID 用于下拉选择。

## 文档处理接口

### 处理文档

- 方法：`POST`
- 路径：`/api/tools/document/process`
- 鉴权：需要
- 请求类型：`multipart/form-data`
- 字段：`file`、`requirement`
- 响应类型：文件下载流
- 响应头：
- `X-Processed-Preview-Base64`：处理结果预览文本的 Base64 编码。
- `X-Process-Mode`：处理模式，例如 `raw-file` 或 `text-fallback`。
