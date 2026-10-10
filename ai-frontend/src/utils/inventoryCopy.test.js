import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const inventoryVue = readFileSync(resolve(__dirname, '../views/Inventory.vue'), 'utf8')

test('inventory page prioritizes risk board over generic SKU query framing', () => {
  assert.match(inventoryVue, /今日风险总览/)
  assert.match(inventoryVue, /库存风险看板/)
  assert.match(inventoryVue, /紧急补货/)
  assert.match(inventoryVue, /临近有效期/)
  assert.match(inventoryVue, /优先处理清单/)
  assert.match(inventoryVue, /风险分级/)
  assert.match(inventoryVue, /生成处理建议/)
  assert.doesNotMatch(inventoryVue, /按商品查询/)
  assert.doesNotMatch(inventoryVue, /已查询 SKU/)
  assert.doesNotMatch(inventoryVue, /最高库存/)
})

test('inventory page includes legend, filter, and action tags', () => {
  assert.match(inventoryVue, /风险图例/)
  assert.match(inventoryVue, /处置建议/)
  assert.match(inventoryVue, /商品辅助检索/)
  assert.match(inventoryVue, /全部风险/)
  assert.match(inventoryVue, /仅看紧急补货/)
  assert.match(inventoryVue, /仅看临近有效期/)
  assert.match(inventoryVue, /建议动作/)
  assert.match(inventoryVue, /优先联系供应商/)
  assert.match(inventoryVue, /转入分析助手/)
  assert.doesNotMatch(inventoryVue, /Inventory Risk Board/)
  assert.doesNotMatch(inventoryVue, /Today Focus/)
  assert.doesNotMatch(inventoryVue, /Priority Queue/)
  assert.doesNotMatch(inventoryVue, /Operator Signal/)
  assert.doesNotMatch(inventoryVue, /SKU Lookup/)
})
