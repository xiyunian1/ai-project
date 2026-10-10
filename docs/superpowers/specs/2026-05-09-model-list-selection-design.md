# Model List Selection Design

## Goal

Let users fetch available OpenAI-compatible models from the configured Base URL and API Key, then select one directly in the system settings page.

## Design

- Backend adds `POST /api/ai/models` and proxies `GET {baseUrl}/models` with `Authorization: Bearer {apiKey}`.
- The endpoint accepts request-level `baseUrl` and `apiKey`; if omitted, it falls back to backend `llm.*` configuration.
- The response normalizes OpenAI-compatible `data[].id` into a simple `models` array.
- Frontend adds a “获取模型” button next to the model field in `Settings.vue`.
- On success, the page shows a selectable model list and writes the selected value to `modelConfig.model`.
- Manual model input remains available when fetching fails or the provider does not expose `/models`.

## Errors

- Missing API key returns a clear `400` message.
- Remote model API errors return `502` with a short user-facing message.
- Frontend displays the backend error without clearing the manually entered model.

## Verification

- Frontend model config tests continue passing.
- Frontend build succeeds.
- Backend `MyAiApplicationTests` succeeds.
