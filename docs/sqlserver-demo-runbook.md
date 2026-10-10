# SQL Server 真实数据演示流程

## 目标

使用本机真实 SQL Server 运行毕业设计演示，完整展示：登录 -> 系统概览 -> 库存预警 -> 生成处理建议 -> 分析助手 -> 流程助手。

## 前置条件

- Windows 本机已安装并启动 SQL Server 默认实例。
- 已安装 `sqlcmd` 命令行工具。
- 后端使用 `ai-project/my-ai` 中的 Maven Wrapper。
- 前端使用 `ai-project/ai-frontend` 中的 npm 脚本。

## 1. 初始化数据库和用户

在 `ai-project/my-ai` 目录执行：

```powershell
sqlcmd -S localhost -E -v MYAI_DB_PASSWORD="<自定义密码>" -i .\init-myai.sql
sqlcmd -S localhost -E -i .\grant-myai.sql
```

初始化脚本会创建：

- 数据库：`myai`
- 登录名：`myai_user`
- 密码：执行时通过 `-v MYAI_DB_PASSWORD=...` 指定
- 数据库用户：`myai_user`
- 数据库角色：`db_owner`

## 2. 确认库存表数据

后端实体映射的库存表为 `dbo.inout`，关键字段包括：

- `按日期`
- `单据类型`
- `供应商名称`
- `商品编号`
- `商品名称`
- `商品规格`
- `库存数量`
- `现有库存`
- `单据编号`
- `有效期至`

低库存预警条件是：`现有库存 < 10`。

执行以下命令检查是否有可演示数据：

```powershell
sqlcmd -S localhost -d myai -E -Q "SELECT TOP 10 [商品编号], [商品名称], [供应商名称], [现有库存], [有效期至] FROM dbo.inout WHERE [现有库存] < 10 ORDER BY [现有库存] ASC"
```

如果返回为空，库存页会显示“当前没有低库存商品”，无法演示“生成处理建议”按钮。此时需要导入真实业务数据，或手动插入少量演示数据。

本项目提供了可选演示数据脚本，只插入/更新 `WMS-DEMO-*` 商品，不删除已有数据：

```powershell
sqlcmd -S localhost -U myai_user -P "<自定义密码>" -d myai -f 65001 -i .\demo-inout-data.sql
```

必须保留 `-f 65001`，避免旧版 SQL Server / `sqlcmd` 环境下脚本编码导致中文列名解析异常。脚本中的演示商品名称使用 ASCII 文本，避免旧实例和驱动组合在前端显示乱码。

## 3. 启动后端

在 `ai-project/my-ai` 目录执行：

```powershell
$env:JWT_SECRET='demo-jwt-secret-should-be-at-least-32-bytes'
$env:DB_URL='jdbc:jtds:sqlserver://localhost:1433/myai'
$env:DB_DRIVER='net.sourceforge.jtds.jdbc.Driver'
$env:DB_USERNAME='myai_user'
$env:DB_PASSWORD='<自定义密码>'
$env:DB_DIALECT='org.hibernate.community.dialect.SQLServerLegacyDialect'
$env:BOOTSTRAP_ADMIN_ENABLED='true'
$env:BOOTSTRAP_ADMIN_USERNAME='admin'
$env:BOOTSTRAP_ADMIN_PASSWORD='admin123'
.\mvnw.cmd spring-boot:run
```

后端默认地址：`http://localhost:8081`。

演示账号：

- 用户名：`admin`
- 密码：`admin123`

## 4. 检查后端接口

后端启动后，在另一个 PowerShell 窗口登录并获取 token：

```powershell
$login = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/auth/login' -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}'
$token = $login.token
```

检查低库存接口：

```powershell
Invoke-RestMethod -Method Get -Uri 'http://localhost:8081/api/tools/low_stock' -Headers @{ Authorization = "Bearer $token" }
```

检查商品查询接口：

```powershell
Invoke-RestMethod -Method Get -Uri 'http://localhost:8081/api/tools/query_stock?productName=苹果' -Headers @{ Authorization = "Bearer $token" }
```

如果低库存接口返回数组且至少一条记录的 `currentStock` 小于 10，前端库存页就能展示低库存卡片和“生成处理建议”按钮。

## 5. 启动前端

在 `ai-project/ai-frontend` 目录执行：

```powershell
npm run dev -- --host 127.0.0.1 --port 5173
```

访问：`http://127.0.0.1:5173`

## 6. 答辩演示路径

1. 打开登录页，使用 `admin / admin123` 登录。
2. 登录后进入 `/dashboard`，说明系统主线和技术架构。
3. 点击“查看库存预警”或左侧“库存洞察”。
4. 在低库存预警卡片中点击“生成处理建议”。
5. 系统跳转到分析助手，并自动填入该商品的低库存风险分析问题。
6. 点击“发送”，展示分析结果和 SQL 明细。
7. 点击左侧“流程助手”，说明补货、供应商沟通、异常处理如何对应业务流程。

SQL 数据库连接和模型 API Key 统一在左侧“系统配置”功能区维护。分析页只展示当前连接摘要，并提供跳转到系统配置的入口。

## 7. 常见问题

### 无法连接 `localhost`

如果执行：

```powershell
sqlcmd -S localhost -E -Q "SELECT @@SERVERNAME"
```

出现登录超时或“无法连接 SQL Server”，先检查 SQL Server 服务状态：

```powershell
Get-Service | Where-Object { $_.Name -like 'MSSQL*' -or $_.Name -like 'SQL*' } | Format-Table -AutoSize Name,Status,DisplayName
```

若 `MSSQLSERVER` 或目标命名实例处于 `Stopped`，需要先启动对应服务。例如默认实例：

```powershell
Start-Service -Name "MSSQLSERVER"
```

命名实例示例：

```powershell
Start-Service -Name "MSSQL`$SQL19"
sqlcmd -S "localhost\SQL19" -E -Q "SELECT @@SERVERNAME"
```

如果所有 SQL Server 服务都启动失败，说明问题在本机 SQL Server 安装或服务配置层。此时需要先通过 SQL Server 配置管理器、Windows 服务日志或重新安装/修复 SQL Server 解决实例启动问题，项目后端无法绕过未启动的数据库服务直接连接真实库。

### 登录失败

- 确认后端启动时设置了 `BOOTSTRAP_ADMIN_ENABLED=true` 和 `BOOTSTRAP_ADMIN_PASSWORD=admin123`。
- 如果数据库里已经存在 `admin` 账号，启动器不会覆盖原密码。可换一个 `BOOTSTRAP_ADMIN_USERNAME`，或在数据库中处理旧账号。

### 低库存数量为 0

- 执行第 2 步 SQL，确认 `dbo.inout` 是否有 `[现有库存] < 10` 的记录。
- 确认后端连接的是 `myai` 数据库，而不是 H2 或其他库。

### 分析页返回 SQL 错误

- 确认真实表字段名与 `Inout` 实体字段一致。
- 确认 `dbo.inout` 中包含用于分析的业务字段。
- 答辩时可说明系统包含 SQL 只读保护、表白名单和 SQL 修复机制，真实分析效果依赖业务表字段质量。

### 前端无法访问后端

- 确认后端端口为 `8081`。
- 确认前端默认 API 地址为 `http://localhost:8081`。
- 如需指定地址，在前端目录创建本地环境变量 `VITE_API_BASE_URL=http://localhost:8081`。
