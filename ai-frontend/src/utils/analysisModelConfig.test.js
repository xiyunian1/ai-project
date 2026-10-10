import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const analysisVue = readFileSync(resolve(__dirname, '../views/Analysis.vue'), 'utf8')

test('analysis page can load persistent model config from local storage', () => {
  assert.match(analysisVue, /loadModelConfigFromLocal/)
  assert.doesNotMatch(analysisVue, /loadActiveModelConfig/)
  assert.match(analysisVue, /localStorage/)
})
