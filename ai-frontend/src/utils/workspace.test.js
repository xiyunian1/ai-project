import test from 'node:test'
import assert from 'node:assert/strict'
import {
  WORKSPACE_FEATURES,
  getWorkspaceFeatureKeyByPath,
  getWorkspaceFeaturesForRole
} from './workspace.js'

test('workspace features include knowledge management instead of workflow helper', () => {
  const labels = WORKSPACE_FEATURES.map((feature) => feature.label)
  const routes = WORKSPACE_FEATURES.map((feature) => feature.route)

  assert.equal(labels.includes('资料管理'), true)
  assert.equal(routes.includes('/knowledge'), true)
  assert.equal(labels.includes('流程助手'), false)
  assert.equal(routes.includes('/workflow'), false)
})

test('knowledge route resolves to knowledge feature key', () => {
  assert.equal(getWorkspaceFeatureKeyByPath('/knowledge'), 'knowledge')
})

test('workspace features include account settings as a standalone feature', () => {
  const accountFeature = WORKSPACE_FEATURES.find((feature) => feature.key === 'account')

  assert.equal(accountFeature?.label, '账号设置')
  assert.equal(accountFeature?.route, '/account')
  assert.equal(accountFeature?.badge, 'USER')
  assert.equal(getWorkspaceFeatureKeyByPath('/account'), 'account')
})

test('admin users can see user management feature', () => {
  const adminFeatures = getWorkspaceFeaturesForRole('ADMIN')
  const userFeatures = getWorkspaceFeaturesForRole('USER')

  assert.equal(adminFeatures.some((feature) => feature.label === '用户管理'), true)
  assert.equal(adminFeatures.some((feature) => feature.route === '/admin/users'), true)
  assert.equal(userFeatures.some((feature) => feature.label === '用户管理'), false)
  assert.equal(userFeatures.some((feature) => feature.route === '/admin/users'), false)
})

test('admin users route resolves to admin users feature key', () => {
  assert.equal(getWorkspaceFeatureKeyByPath('/admin/users'), 'admin-users')
})

test('workspace feature descriptions match graduation WMS modules', () => {
  const descriptions = Object.fromEntries(WORKSPACE_FEATURES.map((feature) => [feature.key, feature.desc]))

  assert.equal(descriptions.chat, '仓储业务问答入口')
  assert.equal(descriptions.analysis, 'SQL 数据分析与结论生成')
  assert.equal(descriptions.inventory, '库存风险看板与处置联动')
  assert.equal(descriptions['admin-users'], '管理员账号与权限控制')
})
