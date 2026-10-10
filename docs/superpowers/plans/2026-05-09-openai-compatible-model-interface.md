# OpenAI-Compatible Model Interface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace active platform-specific model calls in `ai-project` with an OpenAI-compatible chat completions interface.

**Architecture:** The backend exposes the same application APIs but uses `/chat/completions` for internal AI calls. The frontend keeps a single settings center and sends request-level model config using generic OpenAI-compatible fields. Documentation describes OpenAI-compatible/DeepSeek configuration and requires a real model for AI responses.

**Tech Stack:** Spring Boot 3.4, Java 17, WebClient, Vue 3, Vite, Node test runner, Maven Wrapper.

---

## File Structure

- Modify `my-ai/src/main/java/com/example/myai/ChatService.java`: replace platform-specific chat/upload logic with OpenAI-compatible text chat and a clear unsupported message for image analysis.
- Modify `my-ai/src/main/java/com/example/myai/SqlAnalysisService.java`: use generic OpenAI-compatible fields in request-level config.
- Modify `my-ai/src/main/java/com/example/myai/SqlNl2SqlAgentService.java`: default to OpenAI-compatible/DeepSeek planner.
- Modify `my-ai/src/main/java/com/example/myai/ChatController.java`: update model config record field names if needed.
- Modify `my-ai/src/main/resources/application.yml` and `my-ai/src/test/resources/application.yml`: add generic `llm.*` defaults.
- Modify `ai-frontend/src/utils/modelConfig.js` and `ai-frontend/src/utils/modelConfig.test.js`: use generic compatible fields.
- Modify `ai-frontend/src/views/Settings.vue`: use generic compatible UI fields and options.
- Modify docs under `ai-project/`: document active OpenAI-compatible setup.

## Task 1: Frontend Model Config Cleanup

**Files:**
- Modify: `ai-frontend/src/utils/modelConfig.js`
- Modify: `ai-frontend/src/utils/modelConfig.test.js`
- Modify: `ai-frontend/src/views/Settings.vue`

- [ ] **Step 1: Update model config tests first**

Replace platform-specific assertions with generic OpenAI-compatible fields in `ai-frontend/src/utils/modelConfig.test.js`. Expected behavior:

```js
const input = {
  enabled: true,
  provider: 'openai-compatible',
  nl2sqlProvider: 'openai-compatible',
  baseUrl: 'https://api.deepseek.com',
  apiKey: 'model-secret',
  model: 'deepseek-chat'
}

const payload = buildModelConfigPayload(input)
assert.equal(payload.baseUrl, 'https://api.deepseek.com')
assert.equal(payload.apiKey, 'model-secret')
assert.equal(payload.model, 'deepseek-chat')
```

- [ ] **Step 2: Run frontend model config test and verify it fails**

Run in `ai-project/ai-frontend`:

```powershell
node --test src/utils/modelConfig.test.js
```

Expected: failure because `baseUrl`, `apiKey`, and `model` are not implemented yet.

- [ ] **Step 3: Update `modelConfig.js`**

Use this shape:

```js
export const createDefaultModelConfig = () => ({
  enabled: false,
  provider: 'openai-compatible',
  nl2sqlProvider: 'openai-compatible',
  baseUrl: 'https://api.deepseek.com',
  apiKey: '',
  model: 'deepseek-chat'
})
```

`cloneModelConfig` should accept old `deepseekBaseUrl`, `deepseekApiKey`, and `deepseekModel` as migration fallbacks.

- [ ] **Step 4: Update `Settings.vue` model panel**

Provider options should be `openai-compatible` and `deepseek`. The fields should be Base URL, API Key, and Model, bound to `modelConfig.baseUrl`, `modelConfig.apiKey`, and `modelConfig.model`.

- [ ] **Step 5: Run frontend model config test and verify it passes**

Run in `ai-project/ai-frontend`:

```powershell
node --test src/utils/modelConfig.test.js
```

Expected: PASS.

## Task 2: Backend ChatService OpenAI-Compatible Rewrite

**Files:**
- Modify: `my-ai/src/main/java/com/example/myai/ChatService.java`
- Modify: `my-ai/src/main/resources/application.yml`
- Modify: `my-ai/src/test/resources/application.yml`

- [ ] **Step 1: Replace model service config keys**

In `ChatService`, use these fields:

```java
@Value("${llm.api-key:${deepseek.api-key:}}")
private String apiKey;

@Value("${llm.base-url:${deepseek.base-url:https://api.deepseek.com}}")
private String baseUrl;

@Value("${llm.model:${deepseek.model:deepseek-chat}}")
private String model;

@Value("${llm.request-timeout-seconds:20}")
private long requestTimeoutSeconds;
```

- [ ] **Step 2: Replace request body builder**

Use OpenAI-compatible body generation:

```java
private Map<String, Object> buildChatBody(String query, boolean streaming) {
    Map<String, Object> body = new HashMap<>();
    body.put("model", model == null || model.isBlank() ? "deepseek-chat" : model.trim());
    body.put("stream", streaming);
    body.put("messages", List.of(
            Map.of("role", "system", "content", "你是 WMS 智能仓储辅助决策系统的中文助手。"),
            Map.of("role", "user", "content", query == null ? "" : query.trim())
    ));
    return body;
}
```

- [ ] **Step 3: Change endpoint and parsing**

Use `baseUrl + "/chat/completions"`. Blocking responses should parse `choices[0].message.content`. Streaming chunks should parse `choices[0].delta.content`, return raw content chunks, and pass `[DONE]` through.

- [ ] **Step 4: Return clear image unsupported message**

Make `chatWithImage` return a clear message explaining image analysis is unavailable in the OpenAI-compatible text-only demo path. Remove multipart upload helpers and unused imports.

- [ ] **Step 5: Update application configs**

Add top-level `llm:` with empty `api-key`, OpenAI-compatible default `base-url`, `model`, and timeout.

## Task 3: Backend SQL Model Provider Cleanup

**Files:**
- Modify: `my-ai/src/main/java/com/example/myai/SqlAnalysisService.java`
- Modify: `my-ai/src/main/java/com/example/myai/SqlNl2SqlAgentService.java`
- Modify: `my-ai/src/main/java/com/example/myai/ChatController.java`

- [ ] **Step 1: Update request-level model config names**

In `SqlAnalysisService.ModelConnectionConfig`, use generic fields:

```java
String baseUrl,
String apiKey,
String model
```

Keep migration-friendly deepseek fields only if still needed by existing frontend payload during transition.

- [ ] **Step 2: Normalize compatible providers**

Provider normalization should accept `openai-compatible`, `openai`, and `deepseek`.

- [ ] **Step 3: Route `SqlAnalysisService` through chat completions**

Route both `openai-compatible` and `deepseek` through the chat completions request method, using generic base URL/API key/model values first, then environment defaults.

- [ ] **Step 4: Route `SqlNl2SqlAgentService` through chat completions**

Default `nl2sql.provider` to `openai-compatible`, and route compatible providers through the existing DeepSeek-compatible `/chat/completions` request.

- [ ] **Step 5: Compile mentally and remove unused imports/fields**

Remove unused platform-specific constants, methods, log messages, or obsolete local-response messages.

## Task 4: Documentation Cleanup

**Files:**
- Modify: `README.md`
- Modify: `my-ai/HELP.md`
- Modify: `docs/api.md`
- Modify: `docs/graduation-design.md`
- Modify: `docs/sqlserver-demo-runbook.md`
- Modify: `AGENTS.md` if it describes obsolete model-service setup
- Modify: `DEPLOY.md` if it describes obsolete model-service setup

- [ ] **Step 1: Replace active obsolete service setup instructions**

Use generic variables:

```text
LLM_API_KEY
LLM_BASE_URL
LLM_MODEL
LLM_REQUEST_TIMEOUT_SECONDS
```

- [ ] **Step 2: Update API model config examples**

Replace request payload examples with:

```json
{
  "enabled": true,
  "provider": "openai-compatible",
  "nl2sqlProvider": "openai-compatible",
  "baseUrl": "https://api.deepseek.com",
  "apiKey": "sk-xxx",
  "model": "deepseek-chat"
}
```

- [ ] **Step 3: Keep historical design files only if clearly historical**

If old plans/specs mention obsolete model-service setup as historical context, either update them or leave them only if final search verification distinguishes them from active instructions.

## Task 5: Verification

**Files:**
- No code edits unless tests reveal issues.

- [ ] **Step 1: Run frontend utility test**

Run in `ai-project/ai-frontend`:

```powershell
node --test src/utils/modelConfig.test.js
```

Expected: PASS.

- [ ] **Step 2: Run frontend build**

Run in `ai-project/ai-frontend`:

```powershell
npm run build
```

Expected: build completes successfully.

- [ ] **Step 3: Run backend focused tests**

Run in `ai-project/my-ai`:

```powershell
.\mvnw.cmd -Dtest=MyAiApplicationTests test
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Search for remaining obsolete service references**

Search source, config, and docs for obsolete model-service setup names. Expected: no active source/config references. Any remaining docs references must be historical and not setup instructions.

---

## Self-Review Notes

- Spec coverage: backend, frontend, docs, tests, and search verification are covered.
- Placeholder scan: no placeholder steps remain.
- Type consistency: frontend fields are `baseUrl`, `apiKey`, `model`; backend generic config should use the same names.
