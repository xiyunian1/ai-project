# Model List Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a backend-proxied model-list fetch and a frontend selector in settings.

**Architecture:** `ChatController` exposes a small authenticated endpoint backed by `ChatService`. `Settings.vue` calls that endpoint using the current `baseUrl/apiKey`, stores the returned IDs locally, and lets the user select one while keeping manual input.

**Tech Stack:** Spring Boot 3.4, WebClient, Vue 3, Vite, Node test runner, Maven Wrapper.

---

## Tasks

- [ ] Add `ChatService.listModels(baseUrl, apiKey)` to call `/models` and parse `data[].id`.
- [ ] Add `POST /api/ai/models` in `ChatController` returning `{ code, message, models }`.
- [ ] Add Settings UI state, button, API call, and model `<select>` when models are loaded.
- [ ] Update API docs with the new endpoint.
- [ ] Verify with `node --test src/utils/modelConfig.test.js`, `npm run build`, and `./mvnw.cmd -Dtest=MyAiApplicationTests test`.

## Self-Review

- Covers backend proxy, frontend selection, docs, and verification.
- No new dependency is required.
- Manual model input remains available.
