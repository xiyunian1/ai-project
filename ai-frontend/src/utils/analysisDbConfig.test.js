import test from 'node:test'
import assert from 'node:assert/strict'

import {
  buildDbConfigPayload,
  cloneDbConfig,
  createDefaultDbConfig,
  fromStoredDbConfig,
  loadDbConfigFromStorage,
  parseStoredDbConfig,
  toStoredDbConfig
} from './analysisDbConfig.js'

const createMemoryStorage = (key, value) => {
  const store = new Map([[key, value]])
  return {
    getItem: (name) => store.get(name) || null,
    setItem: (name, nextValue) => store.set(name, nextValue)
  }
}

test('toStoredDbConfig removes database password before local persistence', () => {
  const stored = toStoredDbConfig({
    enabled: true,
    server: 'db.local',
    port: 1433,
    database: 'warehouse',
    username: 'readonly',
    password: 'secret-password',
    jdbcUrl: '',
    allowedTables: 'dbo.inout',
    discoverTables: false
  })

  assert.equal(stored.password, '')
  assert.equal(stored.username, 'readonly')
  assert.equal(stored.database, 'warehouse')
  assert.equal(stored.discoverTables, false)
})

test('cloneDbConfig keeps loaded configs usable with blank password', () => {
  const cloned = cloneDbConfig({ enabled: true, database: 'warehouse' })

  assert.deepEqual(cloned, {
    ...createDefaultDbConfig(),
    enabled: true,
    database: 'warehouse'
  })
})

test('fromStoredDbConfig clears legacy passwords loaded from localStorage', () => {
  const loaded = fromStoredDbConfig({
    enabled: true,
    database: 'warehouse',
    password: 'old-local-secret'
  })

  assert.equal(loaded.password, '')
  assert.equal(loaded.enabled, true)
  assert.equal(loaded.database, 'warehouse')
})

test('parseStoredDbConfig marks legacy password entries for storage rewrite', () => {
  const result = parseStoredDbConfig(JSON.stringify({
    ...createDefaultDbConfig(),
    enabled: true,
    database: 'warehouse',
    password: 'old-local-secret'
  }))

  assert.equal(result.config.password, '')
  assert.equal(result.shouldRewrite, true)
})

test('parseStoredDbConfig marks password-free normalization differences for storage rewrite', () => {
  const result = parseStoredDbConfig(JSON.stringify({
    enabled: true,
    database: 'warehouse',
    password: ''
  }))

  assert.equal(result.config.password, '')
  assert.equal(result.shouldRewrite, true)
})

test('loadDbConfigFromStorage rewrites legacy stored passwords', () => {
  const key = 'db-config'
  const storage = createMemoryStorage(key, JSON.stringify({
    ...createDefaultDbConfig(),
    enabled: true,
    database: 'warehouse',
    password: 'old-local-secret'
  }))

  const config = loadDbConfigFromStorage(storage, key)
  const rewritten = JSON.parse(storage.getItem(key))

  assert.equal(config.password, '')
  assert.equal(rewritten.password, '')
  assert.equal(rewritten.database, 'warehouse')
})

test('loadDbConfigFromStorage keeps sanitized config when storage rewrite throws', () => {
  const key = 'db-config'
  const storage = {
    getItem: () => JSON.stringify({
      ...createDefaultDbConfig(),
      enabled: true,
      database: 'warehouse',
      password: 'old-local-secret'
    }),
    setItem: () => {
      throw new Error('storage unavailable')
    }
  }

  const config = loadDbConfigFromStorage(storage, key)

  assert.equal(config.password, '')
  assert.equal(config.database, 'warehouse')
})

test('buildDbConfigPayload always returns the active real database config payload', () => {
  const payload = buildDbConfigPayload({
    enabled: false,
    server: 'db.local',
    port: '1434',
    database: 'warehouse',
    username: 'readonly',
    password: 'secret-password',
    allowedTables: 'dbo.inout',
    discoverTables: true
  })

  assert.equal(payload.password, 'secret-password')
  assert.equal(payload.port, 1434)
  assert.equal(payload.enabled, true)
})

test('buildDbConfigPayload disables custom database payload when connection target is incomplete', () => {
  const payload = buildDbConfigPayload(createDefaultDbConfig())

  assert.equal(payload.enabled, false)
  assert.equal(payload.server, 'localhost')
  assert.equal(payload.database, '')
})
