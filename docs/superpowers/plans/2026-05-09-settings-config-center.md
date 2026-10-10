# Settings Config Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated System Settings feature area for SQL database and model configuration, and remove inline database configuration editing from the analysis page.

**Architecture:** Add a Vue settings page that owns browser-side SQL and model configuration. Reuse the existing analysis DB config storage format so Analysis.vue can keep sending `dbConfig`; add model config storage and include it in analysis stream requests. Backend accepts request-level model config for local demo use without persisting secrets.

**Tech Stack:** Vue 3 SFC, Vue Router, sessionStorage/localStorage, Spring Boot records, Java 17.

---

## File Structure

- Create `ai-frontend/src/views/Settings.vue`: dedicated settings feature page.
- Create `ai-frontend/src/utils/modelConfig.js`: front-end model config defaults, storage, and payload builder.
- Create `ai-frontend/src/utils/modelConfig.test.js`: utility tests for model config storage behavior.
- Modify `ai-frontend/src/router/index.js`: add `/settings` route.
- Modify `ai-frontend/src/utils/workspace.js`: add System Settings nav item and path mapping.
- Modify `ai-frontend/src/views/Analysis.vue`: remove database dialog UI, route settings management to `/settings`, load model config and send `modelConfig` in analysis requests.
- Modify `my-ai/src/main/java/com/example/myai/ChatController.java`: add `modelConfig` to `AnalysisStreamRequest`.
- Modify `my-ai/src/main/java/com/example/myai/SqlAnalysisService.java`: accept model config and use request-level provider/base URL/API key/model when supplied.
- Update docs: `docs/graduation-design.md`, `docs/api.md`, `docs/sqlserver-demo-runbook.md`.

## Task 1: Add Frontend Settings Navigation

**Files:**
- Modify: `ai-frontend/src/utils/workspace.js`
- Modify: `ai-frontend/src/router/index.js`

- [ ] **Step 1: Add settings nav metadata**

Update `WORKSPACE_FEATURES` to include:

```js
{ key: 'settings', label: '系统配置', desc: '数据库与模型配置', route: '/settings', badge: 'CFG' }
```

Add path mapping:

```js
if (path.startsWith('/settings')) return 'settings'
```

- [ ] **Step 2: Register settings route**

Import `Settings` and add a protected route:

```js
import Settings from '../views/Settings.vue'

{ path: '/settings', component: Settings, meta: { requiresAuth: true } }
```

## Task 2: Add Model Config Utility

**Files:**
- Create: `ai-frontend/src/utils/modelConfig.js`
- Create: `ai-frontend/src/utils/modelConfig.test.js`

- [ ] **Step 1: Implement model config utility**

Create defaults with session-only secret behavior:

```js
export const MODEL_CONFIG_STORAGE_KEY = 'WMS_MODEL_CONFIG'

export const createDefaultModelConfig = () => ({
  enabled: false,
  provider: 'openai-compatible',
  nl2sqlProvider: 'openai-compatible',
  baseUrl: 'https://api.deepseek.com',
  apiKey: '',
  model: 'deepseek-chat'
})
```

Add `cloneModelConfig`, `toStoredModelConfig`, `loadModelConfigFromSession`, `saveModelConfigToSession`, and `buildModelConfigPayload`.

- [ ] **Step 2: Add utility tests**

Test that API keys are only persisted to `sessionStorage` payload and payload is null when disabled.

## Task 3: Create Settings Page

**Files:**
- Create: `ai-frontend/src/views/Settings.vue`

- [ ] **Step 1: Create settings layout**

Use `WorkspaceSidebar`, `getWorkspaceFeatureKeyByPath`, `analysisDbConfig` helpers, and `modelConfig` helpers.

- [ ] **Step 2: Add SQL database card**

Fields:

- Enable custom SQL connection
- Server
- Port
- Database
- Username
- Password
- JDBC URL
- Allowed tables
- Discover tables

Save to the same account-scoped key Analysis.vue already uses: `WMS_ANALYSIS_DB_CONFIG_<account>`.

- [ ] **Step 3: Add model config card**

Fields:

- Enable request-level model config
- Provider
- NL2SQL provider
- Base URL
- API key
- Model

Save to `sessionStorage` using `MODEL_CONFIG_STORAGE_KEY`.

## Task 4: Move Analysis Config Management To Settings

**Files:**
- Modify: `ai-frontend/src/views/Analysis.vue`

- [ ] **Step 1: Remove inline DB config dialog from template**

Delete the `<div v-if="dbConfigDialogVisible" class="db-dialog-mask" ...>` block.

- [ ] **Step 2: Change connection action**

Replace `@click="openDbConfig"` with navigation to settings:

```vue
<section class="connection-strip" @click="goSettings">
...
<button class="connection-action" type="button" @click.stop="goSettings">前往系统配置</button>
```

- [ ] **Step 3: Remove unused dialog state and functions**

Remove `dbConfigDialogVisible`, `dbConfigError`, `dbConfigDraft`, `openDbConfig`, `closeDbConfig`, and `saveDbConfig` from Analysis.vue.

- [ ] **Step 4: Load and send model config**

Import `buildModelConfigPayload` and `loadModelConfigFromSession`, then add:

```js
const modelConfig = ref(loadModelConfigFromSession(sessionStorage))
```

Refresh before sending and include in request body:

```js
modelConfig.value = loadModelConfigFromSession(sessionStorage)
modelConfig: buildModelConfigPayload(modelConfig.value)
```

## Task 5: Backend Request-Level Model Config

**Files:**
- Modify: `my-ai/src/main/java/com/example/myai/ChatController.java`
- Modify: `my-ai/src/main/java/com/example/myai/SqlAnalysisService.java`

- [ ] **Step 1: Add request record**

In `ChatController.AnalysisStreamRequest`, add:

```java
SqlAnalysisService.ModelConnectionConfig modelConfig
```

Pass it to `sqlAnalysisService.analyzeStream(request.query(), request.history(), request.dbConfig(), request.modelConfig())`.

- [ ] **Step 2: Add model config record and overload**

In `SqlAnalysisService`, add:

```java
public record ModelConnectionConfig(
        Boolean enabled,
        String provider,
        String nl2sqlProvider,
        String baseUrl,
        String apiKey,
        String model
) {}
```

Add an overload for `analyzeStream(rawQuery, history, dbConfig, modelConfig)` and a ThreadLocal/request context or direct parameter path so `requestAgentDecision` uses model config values when enabled.

- [ ] **Step 3: Preserve explicit error behavior when external services are unavailable**

If `modelConfig` is null or disabled, keep using existing `@Value` fields.

## Task 6: Documentation And Verification

**Files:**
- Modify: `docs/graduation-design.md`
- Modify: `docs/api.md`
- Modify: `docs/sqlserver-demo-runbook.md`

- [ ] **Step 1: Document settings feature**

Mention `/settings` as the home for SQL and model configuration.

- [ ] **Step 2: Document analysis request body**

Update `docs/api.md` to show optional `modelConfig`.

- [ ] **Step 3: Run verification**

Run:

```powershell
npm test
npm run build
```

If backend files changed, run:

```powershell
.\mvnw.cmd -Dtest=MyAiApplicationTests test
```

## Self-Review

- Spec coverage: settings page, nav, SQL config relocation, model API key UI, analysis request payload, docs, and tests are covered.
- Security note: model keys are stored in `sessionStorage`, not database or localStorage.
- Scope: no persistent backend secret storage and no unrelated UI redesign.
