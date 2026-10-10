import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const analysisVue = readFileSync(resolve(__dirname, '../views/Analysis.vue'), 'utf8')

test('analysis page presents the database as a fixed real connection path', () => {
  assert.match(analysisVue, /真实数据库连接/)
  assert.match(analysisVue, /当前使用真实数据库连接/)
  assert.match(analysisVue, /真实数据库/)
  assert.doesNotMatch(analysisVue, /默认连接/)
  assert.doesNotMatch(analysisVue, /自定义连接/)
  assert.doesNotMatch(analysisVue, /连接可切换/)
  assert.doesNotMatch(analysisVue, /先切换到自定义 SQL Server 连接/)
})
