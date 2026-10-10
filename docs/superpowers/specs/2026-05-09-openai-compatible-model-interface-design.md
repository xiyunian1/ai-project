# Use OpenAI-Compatible Model Interface

## Goal

Replace the previous platform-specific model integration surface with an OpenAI-compatible chat completions interface so the graduation project can use DeepSeek or any compatible gateway.

## Scope

- Only modify `ai-project/ai-frontend`, `ai-project/my-ai`, and `ai-project` documentation.
- Do not modify external platform repositories.
- Preserve existing WMS pages, SQL analysis behavior, and authentication behavior while requiring a real model configuration for AI responses.
- Do not introduce new dependencies unless existing Spring WebFlux/WebClient or frontend utilities are insufficient.

## Backend Design

`ChatService` will use an OpenAI-compatible `/chat/completions` request body with `model`, `messages`, and optional `stream`. If API configuration is absent or a request fails, the service returns a clear configuration/service error instead of generating a local demo response. Image chat returns a clear unsupported message unless later extended with a compatible multimodal provider.

`SqlAnalysisService` and `SqlNl2SqlAgentService` model-planning calls will use the same OpenAI-compatible request shape. DeepSeek remains the default practical example because its API is compatible with chat completions, but the configuration and UI should be described generically as OpenAI-compatible.

Runtime keys should use generic names such as `llm.api-key`, `llm.base-url`, `llm.model`, and request timeout settings. Existing `deepseek.*` values may be retained as defaults where already used, but the project documentation should present OpenAI-compatible model configuration as the active path.

## Frontend Design

`Settings.vue` will expose a generic OpenAI-compatible Base URL, API Key, and Model, with DeepSeek shown as the default example.

`modelConfig.js` will use generic compatible-model fields in defaults, cloning, storage, and payload generation. Analysis requests will continue sending `modelConfig`.

## Documentation Design

Project docs will document AI integration as OpenAI-compatible chat completions with real model configuration required for demonstrations.

## Testing

- Frontend: run existing model config tests and `npm run build` in `ai-frontend`.
- Backend: run at least `./mvnw.cmd -Dtest=MyAiApplicationTests test` and a focused SQL analysis test if affected by provider changes.
- Search verification: confirm no platform-specific model service references remain in active source/config/docs.

## Risks

- Streaming response parsing must follow OpenAI-compatible SSE payloads.
- Image-chat behavior returns an unsupported message; this is acceptable because the project goal is WMS text and SQL analysis, not multimodal upload.
- Existing stored browser session config may contain obsolete fields; cloning should ignore them without breaking current defaults.
