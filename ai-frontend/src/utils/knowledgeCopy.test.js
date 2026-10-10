import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const knowledgeVue = readFileSync(resolve(__dirname, '../views/Knowledge.vue'), 'utf8')

test('knowledge page behaves like a knowledge hub instead of a plain upload list', () => {
  assert.match(knowledgeVue, /资料知识库/)
  assert.match(knowledgeVue, /eyebrow="资料管理"/)
  assert.match(knowledgeVue, /知识库总览/)
  assert.match(knowledgeVue, /资料分类/)
  assert.match(knowledgeVue, /引用关系/)
  assert.match(knowledgeVue, /流程制度/)
  assert.match(knowledgeVue, /商品与库存资料/)
  assert.match(knowledgeVue, /培训与说明资料/)
  assert.match(knowledgeVue, /智能对话优先引用/)
  assert.doesNotMatch(knowledgeVue, /已上传资料</)
  assert.doesNotMatch(knowledgeVue, /Knowledge Hub/)
  assert.doesNotMatch(knowledgeVue, /Knowledge Snapshot/)
  assert.doesNotMatch(knowledgeVue, /Knowledge Groups/)
  assert.doesNotMatch(knowledgeVue, /Reference Flow/)
  assert.doesNotMatch(knowledgeVue, /Ingest/)
})
