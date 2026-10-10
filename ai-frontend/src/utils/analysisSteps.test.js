import test from 'node:test'
import assert from 'node:assert/strict'
import {
  completeAnalysisSteps,
  createInitialAnalysisSteps,
  resolveAnalysisStepKey,
  updateAnalysisSteps
} from './analysisSteps.js'

test('initial analysis steps start with understanding active', () => {
  const steps = createInitialAnalysisSteps()

  assert.deepEqual(steps.map((step) => step.key), ['understand', 'plan', 'query', 'summary'])
  assert.equal(steps[0].status, 'active')
  assert.equal(steps[1].status, 'pending')
})

test('status text maps to analysis step keys', () => {
  assert.equal(resolveAnalysisStepKey('正在理解问题...'), 'understand')
  assert.equal(resolveAnalysisStepKey('正在思考分析路径 (step 1/4)...'), 'plan')
  assert.equal(resolveAnalysisStepKey('正在执行 SQL 查询...'), 'query')
  assert.equal(resolveAnalysisStepKey('正在生成结论和建议...'), 'summary')
})

test('updating a later step marks previous steps done', () => {
  const steps = updateAnalysisSteps(createInitialAnalysisSteps(), '正在执行 SQL 查询...')

  assert.equal(steps[0].status, 'done')
  assert.equal(steps[1].status, 'done')
  assert.equal(steps[2].status, 'active')
  assert.equal(steps[2].detail, '正在执行 SQL 查询...')
  assert.equal(steps[3].status, 'pending')
})

test('completeAnalysisSteps marks all steps done', () => {
  const steps = completeAnalysisSteps(updateAnalysisSteps(createInitialAnalysisSteps(), '正在执行 SQL 查询...'))

  assert.deepEqual(steps.map((step) => step.status), ['done', 'done', 'done', 'done'])
})
