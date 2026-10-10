import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const settingsVue = readFileSync(resolve(__dirname, '../views/Settings.vue'), 'utf8')

test('settings treats database configuration as the real active connection path', () => {
  assert.match(settingsVue, /class="setting-section-title"/)
  assert.match(settingsVue, /配置中心/)
  assert.match(settingsVue, /数据库配置/)
  assert.match(settingsVue, /分析助手使用这里保存的真实数据库连接配置/)
  assert.match(settingsVue, /连接参数/)
  assert.doesNotMatch(settingsVue, /连接来源/)
  assert.doesNotMatch(settingsVue, /启用自定义数据库连接/)
  assert.doesNotMatch(settingsVue, /默认连接/)
  assert.doesNotMatch(settingsVue, /自定义连接/)
  assert.doesNotMatch(settingsVue, /模型来源/)
  assert.doesNotMatch(settingsVue, /启用请求级模型配置/)
  assert.doesNotMatch(settingsVue, /modelConfig\.enabled/)
  assert.equal(settingsVue.includes('mock'), false)
  assert.equal(settingsVue.includes('模拟回复'), false)
  assert.doesNotMatch(settingsVue, /eyebrow="System Settings"/)
  assert.doesNotMatch(settingsVue, />Configuration Center</)
  assert.doesNotMatch(settingsVue, />Database</)
  assert.doesNotMatch(settingsVue, />Model</)
})

test('settings page explains persistent model config and flexible model name', () => {
  assert.match(settingsVue, /模型配置会保存到本机浏览器/)
  assert.match(settingsVue, /下次打开会自动带出/)
  assert.match(settingsVue, /本页保存配置优先于后端环境变量/)
  assert.match(settingsVue, /如：deepseek-chat \/ qwen-plus \/ gpt-4o-mini/)
  assert.match(settingsVue, /saveModelConfigToLocal\(localStorage, modelConfig\.value\)/)
  assert.match(settingsVue, /loadModelConfigFromLocal\(localStorage\)/)
})
