import test from 'node:test'
import assert from 'node:assert/strict'

import { decodeAccountFromToken, resolveAccountKey } from './auth.js'

const encodeBase64Url = (value) => Buffer
  .from(JSON.stringify(value), 'utf8')
  .toString('base64url')

test('decodeAccountFromToken returns JWT subject', () => {
  const token = `${encodeBase64Url({ alg: 'none' })}.${encodeBase64Url({ sub: 'alice' })}.signature`

  assert.equal(decodeAccountFromToken(token), 'alice')
})

test('decodeAccountFromToken decodes UTF-8 JWT subject', () => {
  const token = `${encodeBase64Url({ alg: 'none' })}.${encodeBase64Url({ sub: '张三' })}.signature`

  assert.equal(decodeAccountFromToken(token), '张三')
})

test('decodeAccountFromToken returns empty string for invalid token', () => {
  assert.equal(decodeAccountFromToken('not-a-jwt'), '')
})

test('decodeAccountFromToken returns empty string for two-segment token', () => {
  const token = `${encodeBase64Url({ alg: 'none' })}.${encodeBase64Url({ sub: 'alice' })}`

  assert.equal(decodeAccountFromToken(token), '')
})

test('decodeAccountFromToken returns empty string for non-string subject', () => {
  const token = `${encodeBase64Url({ alg: 'none' })}.${encodeBase64Url({ sub: {} })}.signature`

  assert.equal(decodeAccountFromToken(token), '')
})

test('decodeAccountFromToken returns empty string for invalid UTF-8 payload', () => {
  const header = encodeBase64Url({ alg: 'none' })
  const payload = Buffer.from([0x7b, 0x22, 0x73, 0x75, 0x62, 0x22, 0x3a, 0x22, 0x80, 0x22, 0x7d])
    .toString('base64url')

  assert.equal(decodeAccountFromToken(`${header}.${payload}.signature`), '')
})

test('resolveAccountKey keeps account token name precedence', () => {
  const token = `${encodeBase64Url({ alg: 'none' })}.${encodeBase64Url({ sub: 'token-user' })}.signature`

  assert.equal(resolveAccountKey({ account: ' stored-user ', token, name: 'named-user' }), 'stored-user')
  assert.equal(resolveAccountKey({ account: '', token, name: 'named-user' }), 'token-user')
  assert.equal(resolveAccountKey({ account: '', token: '', name: ' named-user ' }), 'named-user')
  assert.equal(resolveAccountKey({ account: '', token: '', name: '' }), 'guest')
})

test('resolveAccountKey preserves current whitespace account fallback behavior', () => {
  const token = `${encodeBase64Url({ alg: 'none' })}.${encodeBase64Url({ sub: 'token-user' })}.signature`

  assert.equal(resolveAccountKey({ account: '   ', token, name: 'named-user' }), 'guest')
})
