# Graduation Warehouse Decision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the existing WMS AI workspace into a graduation-design-ready intelligent warehouse decision system with a dashboard, inventory-to-analysis flow, and supporting docs.

**Architecture:** Keep the current Vue 3 frontend and Spring Boot backend boundaries. Add one frontend dashboard page, extend the shared workspace navigation, reuse existing inventory and analysis APIs, and document the system without adding new backend tables.

**Tech Stack:** Vue 3 SFC with `script setup`, Vue Router, Vite, native `fetch`, Spring Boot 3.4, Java 17, Markdown docs.

---

## File Structure

- Create `ai-frontend/src/views/Dashboard.vue`: authenticated system overview page for graduation demonstration.
- Modify `ai-frontend/src/router/index.js`: register `/dashboard` and keep route guard behavior.
- Modify `ai-frontend/src/utils/workspace.js`: add dashboard navigation metadata and path mapping.
- Modify `ai-frontend/src/views/Login.vue`: redirect successful login to `/dashboard`.
- Modify `ai-frontend/src/views/Inventory.vue`: add low-stock “生成建议” action and route to analysis with a generated query.
- Modify `ai-frontend/src/views/Analysis.vue`: consume `route.query.query` as a prefilled analysis prompt without auto-sending.
- Create `docs/graduation-design.md`: graduation design overview and demo flow.
- Create `docs/api.md`: API reference for the main backend endpoints.
- Create `docs/database.md`: database/entity design notes.

## Task 1: Add Dashboard Route And Navigation

**Files:**
- Create: `ai-frontend/src/views/Dashboard.vue`
- Modify: `ai-frontend/src/router/index.js`
- Modify: `ai-frontend/src/utils/workspace.js`
- Modify: `ai-frontend/src/views/Login.vue`

- [ ] **Step 1: Add dashboard metadata to workspace navigation**

Update `ai-frontend/src/utils/workspace.js` to include dashboard first and map `/dashboard`.

```js
export const WORKSPACE_FEATURES = [
  { key: 'dashboard', label: '系统概览', desc: '毕设展示入口', route: '/dashboard', badge: 'WMS' },
  { key: 'chat', label: '智能对话', desc: '对话式工作台', route: '/chat', badge: 'AI' },
  { key: 'analysis', label: '分析助手', desc: '对话式数据分析', route: '/analysis', badge: 'BI' },
  { key: 'inventory', label: '库存洞察', desc: '库存与预警分析', route: '/inventory', badge: 'INV' },
  { key: 'workflow', label: '流程助手', desc: '入库出库流程', route: '/workflow', badge: 'OPS' },
  { key: 'document', label: '文档处理', desc: '上传文档并按要求处理', route: '/document-process', badge: 'DOC' }
]

export const getWorkspaceFeatureKeyByPath = (path = '') => {
  if (path.startsWith('/dashboard')) return 'dashboard'
  if (path.startsWith('/analysis')) return 'analysis'
  if (path.startsWith('/inventory')) return 'inventory'
  if (path.startsWith('/workflow')) return 'workflow'
  if (path.startsWith('/document-process')) return 'document'
  return 'chat'
}

export const getWorkspaceFeatureBadge = (key = '') => {
  return WORKSPACE_FEATURES.find((item) => item.key === key)?.badge || 'APP'
}
```

- [ ] **Step 2: Create dashboard page**

Create `ai-frontend/src/views/Dashboard.vue` with a lightweight overview that uses `WorkspaceSidebar`, current theme utilities, auth headers, and the existing low-stock endpoint.

- [ ] **Step 3: Register dashboard route**

Update `ai-frontend/src/router/index.js` imports and routes.

```js
import Dashboard from '../views/Dashboard.vue'

const routes = [
  { path: '/', redirect: '/login' },
  { path: '/login', component: Login },
  { path: '/register', component: Register },
  { path: '/dashboard', component: Dashboard, meta: { requiresAuth: true } },
  { path: '/chat', component: Chat, meta: { requiresAuth: true } },
  { path: '/analysis', component: Analysis, meta: { requiresAuth: true } },
  { path: '/inventory', component: Inventory, meta: { requiresAuth: true } },
  { path: '/workflow', component: Workflow, meta: { requiresAuth: true } },
  { path: '/document-process', component: DocumentProcess, meta: { requiresAuth: true } }
]
```

- [ ] **Step 4: Redirect login to dashboard**

In `ai-frontend/src/views/Login.vue`, change successful login redirect.

```js
router.push('/dashboard')
```

Expected replacement: the current `router.push('/chat')` inside `handleLogin`.

## Task 2: Implement Inventory-To-Analysis Flow

**Files:**
- Modify: `ai-frontend/src/views/Inventory.vue`
- Modify: `ai-frontend/src/views/Analysis.vue`

- [ ] **Step 1: Add low-stock analysis button**

In `Inventory.vue`, inside each `.warning-card`, add a button after the stock/expiry text.

```vue
<button class="warning-action" type="button" @click="analyzeLowStock(row)">生成处理建议</button>
```

- [ ] **Step 2: Add low-stock query builder**

In `Inventory.vue` script, add `analyzeLowStock` near `selectFeature`.

```js
const analyzeLowStock = (row) => {
  const productName = row?.productName || '未命名商品'
  const currentStock = row?.currentStock ?? row?.stockQty ?? '未知'
  const productId = row?.productId ? `，商品编号 ${row.productId}` : ''
  const supplier = row?.supplierName ? `，供应商 ${row.supplierName}` : ''
  const query = `请根据商品“${productName}”${productId}${supplier}，当前库存 ${currentStock}，分析低库存风险，并给出补货、供应商沟通和流程处理建议。`
  router.push({ path: '/analysis', query: { query } })
}
```

- [ ] **Step 3: Style the warning action**

Add scoped CSS in `Inventory.vue`.

```css
.warning-action {
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid rgba(79, 123, 255, 0.24);
  border-radius: var(--radius-pill);
  color: var(--brand-700);
  background: rgba(79, 123, 255, 0.1);
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
}
```

- [ ] **Step 4: Prefill analysis input from route query**

In `Analysis.vue`, add a helper before `onMounted`.

```js
const applyRouteQueryPrompt = () => {
  const routeQuery = route.query.query
  const prompt = Array.isArray(routeQuery) ? routeQuery[0] : routeQuery
  if (!prompt || typeof prompt !== 'string') return
  if (inputVal.value.trim()) return
  inputVal.value = prompt.slice(0, 2000)
}
```

Call it at the end of `onMounted`, after session creation/switching.

```js
  applyRouteQueryPrompt()
```

Do not auto-send.

## Task 3: Add Graduation Design Documentation

**Files:**
- Create: `docs/graduation-design.md`
- Create: `docs/api.md`
- Create: `docs/database.md`

- [ ] **Step 1: Write graduation design overview**

Create `docs/graduation-design.md` with these sections:

```markdown
# WMS 智能仓储辅助决策系统毕业设计说明

## 课题名称
基于 Spring Boot 与 Vue 的 WMS 智能仓储辅助决策系统设计与实现

## 系统定位
本系统面向仓储运营场景，围绕库存查询、低库存预警、自然语言数据分析、流程指导和智能问答构建统一工作台。

## 功能模块
- 用户认证：注册、登录、JWT 鉴权、修改密码。
- 系统概览：展示课题主线、核心能力和演示入口。
- 库存洞察：查询商品库存、展示低库存预警。
- 分析助手：支持自然语言库存分析、SQL 明细回看和连续追问。
- 流程助手：展示入库、出库和异常处理流程。
- 文档处理：上传文档、填写处理要求、预览和下载处理结果。

## 技术架构
- 前端：Vue 3、Vite、Vue Router、Electron 可选打包。
- 后端：Spring Boot、Spring MVC/WebFlux、Spring Data JPA、JWT。
- 数据库：SQL Server 业务库，测试环境使用 H2。
- AI 集成：OpenAI 兼容模型接口、DeepSeek/NL2SQL，演示时使用真实模型调用。

## 演示流程
1. 登录系统并进入系统概览。
2. 查看核心模块和演示路径。
3. 进入库存洞察查看低库存预警。
4. 点击低库存商品的生成处理建议。
5. 在分析助手中发送预置问题，查看分析结果和 SQL 明细。
6. 切换流程助手，说明建议如何对应仓储处理流程。
```

- [ ] **Step 2: Write API documentation**

Create `docs/api.md` covering endpoint, method, auth requirement, request, and response summary for auth, inventory, chat, analysis, and document endpoints.

- [ ] **Step 3: Write database documentation**

Create `docs/database.md` covering `UserAccount`, `UserChatSession`, `UserChatHistory`, and `Inout` responsibilities and key fields.

## Task 4: Verify Build And Targeted Behavior

**Files:**
- Verify: `ai-frontend/package.json`
- Verify: `my-ai/pom.xml` only if backend changes are introduced

- [ ] **Step 1: Run frontend build**

Run in `ai-project/ai-frontend`:

```powershell
npm run build
```

Expected: Vite build completes successfully.

- [ ] **Step 2: Run frontend utility tests if build passes**

Run in `ai-project/ai-frontend`:

```powershell
npm test
```

Expected: Node tests for `src/utils/*.test.js` pass.

- [ ] **Step 3: Backend verification**

If no backend code changed, record “backend not changed; Maven tests not required for this task.” If backend code changes unexpectedly, run in `ai-project/my-ai`:

```powershell
.\mvnw.cmd -Dtest=MyAiApplicationTests test
```

Expected: test passes.

## Self-Review

- Spec coverage: dashboard, login redirect, navigation, inventory-to-analysis flow, docs, and verification are covered.
- Scope: no backend schema changes and no external AI dependency changes.
- Placeholder scan: no TBD/TODO placeholders remain in implementation steps.
- Type consistency: uses existing `route`, `router`, `inputVal`, `WorkspaceSidebar`, `withAuthHeaders`, and `buildApiUrl` patterns already present in the codebase.
