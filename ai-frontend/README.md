# ai-frontend

WMS 智能助手前端（Vue 3 + Vite + Electron）。

## 运行

```powershell
npm install
npm run dev -- --host 127.0.0.1 --port 5173
```

默认地址：`http://127.0.0.1:5173`

## 构建

```powershell
npm run build
```

Electron 打包：

```powershell
npm run electron:build
```

## 环境变量

创建 `.env.local`：

```env
VITE_API_BASE_URL=http://localhost:8081
```

## 页面

- `/login` 登录
- `/register` 注册
- `/chat` 智能对话
- `/inventory` 库存洞察
- `/workflow` 流程助手
