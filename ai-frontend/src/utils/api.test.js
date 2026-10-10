import test from 'node:test'
import assert from 'node:assert/strict'

import { buildApiUrl, parseJsonSafe } from './api.js'

test('buildApiUrl joins base and path without duplicate slashes', () => {
  assert.equal(
    buildApiUrl('/api/auth/login', undefined, 'http://localhost:8081/'),
    'http://localhost:8081/api/auth/login'
  )
})

test('buildApiUrl appends query params and skips empty values', () => {
  assert.equal(
    buildApiUrl('api/tools/query_stock', { productName: 'A B', empty: '', missing: null }, 'http://localhost:8081'),
    'http://localhost:8081/api/tools/query_stock?productName=A+B'
  )
})

test('parseJsonSafe returns parsed JSON when response body is valid', async () => {
  const response = new Response('{"ok":true}', {
    headers: { 'Content-Type': 'application/json' }
  })

  assert.deepEqual(await parseJsonSafe(response), { ok: true })
})

test('parseJsonSafe returns null when response body is not JSON', async () => {
  const response = new Response('not-json', {
    headers: { 'Content-Type': 'text/plain' }
  })

  assert.equal(await parseJsonSafe(response), null)
})
