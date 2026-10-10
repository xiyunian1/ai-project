# 数据库设计说明

## 数据库定位

系统后端默认连接 SQL Server，用于保存用户账号、会话记录和仓储业务数据。测试环境使用 H2 内存数据库，以便执行自动化测试时不依赖真实 SQL Server。

## 用户账号表：`user_account`

对应实体：`UserAccount`

主要职责：保存登录账号、展示名称和加密后的密码。

关键字段：

- `id`：主键。
- `username`：登录账号，要求唯一。
- `displayName`：用户展示名称。
- `passwordHash`：加密后的密码摘要。
- `createdAt`：创建时间。
- `updatedAt`：更新时间。

相关功能：注册、登录、修改密码、JWT 生成。

## 聊天会话表：`user_chat_session`

对应实体：`UserChatSession`

主要职责：保存用户的会话分组信息，用于聊天页和分析页恢复历史上下文。

关键字段：

- `id`：主键。
- `username`：所属用户账号。
- `sessionId`：前端会话标识。
- `name`：会话名称。
- `createdAt`：创建时间。
- `updatedAt`：更新时间。

相关功能：最近会话、会话重命名、会话删除。

## 聊天历史表：`user_chat_history`

对应实体：`UserChatHistory`

主要职责：保存用户和助手之间的历史消息。

关键字段：

- `id`：主键。
- `username`：所属用户账号。
- `sessionId`：所属会话标识。
- `role`：消息角色，例如 `user` 或 `assistant`。
- `content`：消息内容。
- `createdAt`：创建时间。

相关功能：历史消息保存、会话上下文恢复、连续追问。

## 仓储业务表：`inout`

对应实体：`Inout`

主要职责：保存仓储出入库和库存相关业务记录，是库存查询、低库存预警和 SQL 分析的核心数据来源。

常用字段：

- `id`：主键。
- `productId`：商品编号。
- `productName`：商品名称。
- `currentStock`：现有库存。
- `stockQty`：库存数量。
- `supplierName`：供应商名称。
- `expiryDate`：有效期。

相关功能：

- `/api/tools/query_stock` 按商品名称查询库存。
- `/api/tools/low_stock` 获取低库存预警。
- `/api/ai/chat/sql` 和 `/api/ai/chat/analyze/stream` 基于该表进行自然语言查询和分析。

## 数据安全设计

- 用户密码不保存明文，只保存密码摘要。
- 前端访问受保护接口时必须携带 JWT。
- SQL 分析服务限制危险关键字，避免执行写入、删除、建表和系统过程调用等高风险语句。
- 测试配置使用 H2，避免测试过程误操作真实业务库。
