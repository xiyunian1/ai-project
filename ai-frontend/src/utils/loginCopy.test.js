import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const loginVue = readFileSync(resolve(__dirname, '../views/Login.vue'), 'utf8')

test('login page keeps a concise showcase', () => {
  assert.doesNotMatch(loginVue, /智能问答系统/)
  assert.doesNotMatch(loginVue, /class="login-intro"/)
  assert.doesNotMatch(loginVue, /class="login-intro-header"/)
  assert.doesNotMatch(loginVue, /class="login-intro-body"/)
  assert.doesNotMatch(loginVue, /WMS 智能仓储辅助决策系统/)
  assert.doesNotMatch(loginVue, /库存洞察、资料问答与分析助手/)
  assert.doesNotMatch(loginVue, /Inventory Watch/)
  assert.doesNotMatch(loginVue, /Knowledge Answer/)
  assert.doesNotMatch(loginVue, /Analysis Console/)
  assert.doesNotMatch(loginVue, /class="auth-grid login-capability-grid"/)
  assert.doesNotMatch(loginVue, /class="auth-capability"/)
  assert.doesNotMatch(loginVue, /文档处理/)
  assert.doesNotMatch(loginVue, /功能闭环/)
  assert.doesNotMatch(loginVue, /答辩友好/)
})
