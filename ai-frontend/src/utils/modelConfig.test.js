import test from 'node:test'
import assert from 'node:assert/strict'
import {
  MODEL_CONFIG_STORAGE_KEY,
  buildModelConfigPayload,
  clearModelConfigFromLocal,
  createDefaultModelConfig,
  loadModelConfigFromLocal,
  loadModelConfigFromSession,
  saveModelConfigToLocal,
  saveModelConfigToSession
} from './modelConfig.js'

const createStorage = () => {
  const values = new Map()
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key)
  }
}

test('buildModelConfigPayload returns saved real model config without an enable switch', () => {
  const payload = buildModelConfigPayload(createDefaultModelConfig())

  assert.equal(payload.enabled, true)
  assert.equal(payload.provider, 'openai-compatible')
  assert.equal(Object.hasOwn(payload, 'enabled'), true)
})

test('default model is blank so users choose their own model', () => {
  assert.equal(createDefaultModelConfig().model, '')
})

test('model config persists API keys only in session storage helper', () => {
  const storage = createStorage()
  const config = {
    ...createDefaultModelConfig(),
    apiKey: 'model-secret'
  }

  saveModelConfigToSession(storage, config)
  const stored = JSON.parse(storage.getItem(MODEL_CONFIG_STORAGE_KEY))

  assert.equal(stored.apiKey, 'model-secret')
  assert.deepEqual(loadModelConfigFromSession(storage), stored)
})

test('model config persists across browser sessions in local storage helper', () => {
  const storage = createStorage()
  const config = {
    provider: 'openai-compatible',
    baseUrl: 'https://example.com/v1',
    apiKey: 'persistent-secret',
    model: 'qwen-plus'
  }

  saveModelConfigToLocal(storage, config)
  assert.deepEqual(loadModelConfigFromLocal(storage), config)

  clearModelConfigFromLocal(storage)
  assert.deepEqual(loadModelConfigFromLocal(storage), createDefaultModelConfig())
})

test('buildModelConfigPayload trims URL and model fields', () => {
  const payload = buildModelConfigPayload({
    ...createDefaultModelConfig(),
    provider: ' openai-compatible ',
    nl2sqlProvider: ' openai-compatible ',
    baseUrl: ' https://api.deepseek.com ',
    model: ' deepseek-chat ',
    apiKey: 'secret'
  })

  assert.equal(payload.provider, 'openai-compatible')
  assert.equal(Object.hasOwn(payload, 'nl2sqlProvider'), false)
  assert.equal(payload.baseUrl, 'https://api.deepseek.com')
  assert.equal(payload.model, 'deepseek-chat')
  assert.equal(payload.apiKey, 'secret')
})

test('model payload uses one provider for all modules', () => {
  const payload = buildModelConfigPayload({
    ...createDefaultModelConfig(),
    provider: 'deepseek',
    nl2sqlProvider: 'openai-compatible',
    apiKey: 'secret'
  })

  assert.deepEqual(Object.keys(payload).sort(), ['apiKey', 'baseUrl', 'enabled', 'model', 'provider'].sort())
  assert.equal(payload.provider, 'deepseek')
})

test('cloneModelConfig migrates previous DeepSeek fields', () => {
  const payload = buildModelConfigPayload({
    provider: 'deepseek',
    nl2sqlProvider: 'deepseek',
    deepseekBaseUrl: 'https://api.deepseek.com',
    deepseekApiKey: 'deepseek-secret',
    deepseekModel: 'deepseek-chat'
  })

  assert.equal(payload.baseUrl, 'https://api.deepseek.com')
  assert.equal(payload.apiKey, 'deepseek-secret')
  assert.equal(payload.model, 'deepseek-chat')
})
