import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const chatVue = readFileSync(resolve(__dirname, '../views/Chat.vue'), 'utf8')

test('chat page does not expose database question mode', () => {
  assert.equal(chatVue.includes('数据库问答'), false)
  assert.equal(chatVue.includes('SQL Server 模式'), false)
  assert.equal(chatVue.includes('SQL 辅助查询'), false)
  assert.equal(chatVue.includes('一键切换 SQL 问答'), false)
  assert.equal(chatVue.includes('dbChatEnabled'), false)
})

test('chat stream sends saved model config to backend', () => {
  assert.match(chatVue, /loadModelConfigFromLocal/)
  assert.match(chatVue, /buildModelConfigPayload/)
  assert.match(chatVue, /\/api\/ai\/chat\/stream/)
  assert.match(chatVue, /method:\s*'POST'/)
  assert.match(chatVue, /modelConfig:\s*buildModelConfigPayload/)
  assert.doesNotMatch(chatVue, /buildApiUrl\('\/api\/ai\/chat\/stream',\s*\{\s*query\s*\}\)/)
  assert.match(chatVue, /parseJsonSafe\(res\)/)
  assert.match(chatVue, /data\?\.message/)
})

test('chat quick prompts focus on knowledge and process guidance', () => {
  assert.match(chatVue, /根据已上传资料，整理一份库存异常排查清单/)
  assert.match(chatVue, /帮我解释入库流程里最容易出错的环节/)
  assert.match(chatVue, /请根据资料说明低库存预警后的处理流程/)
  assert.doesNotMatch(chatVue, /帮我总结今天需要重点关注的库存风险/)
  assert.doesNotMatch(chatVue, /根据当前业务数据，给我一个低库存处理建议/)
})
