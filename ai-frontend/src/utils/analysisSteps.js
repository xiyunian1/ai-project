export const ANALYSIS_STEPS = [
  { key: 'understand', label: '理解问题', desc: '识别分析目标和上下文' },
  { key: 'plan', label: '规划 SQL', desc: '选择指标、表和查询路径' },
  { key: 'query', label: '执行查询', desc: '读取 SQL Server 业务数据' },
  { key: 'summary', label: '生成结论', desc: '汇总风险、发现和建议' }
]

export const createInitialAnalysisSteps = () => ANALYSIS_STEPS.map((step, index) => ({
  ...step,
  status: index === 0 ? 'active' : 'pending',
  detail: ''
}))

export const resolveAnalysisStepKey = (message = '') => {
  const text = String(message || '').toLowerCase()
  if (/sql|查询|query|执行|tool|数据/.test(text)) return 'query'
  if (/路径|计划|规划|step|思考|分析/.test(text)) return 'plan'
  if (/结论|总结|建议|完成|生成/.test(text)) return 'summary'
  return 'understand'
}

export const updateAnalysisSteps = (steps, message = '') => {
  const activeKey = resolveAnalysisStepKey(message)
  const activeIndex = ANALYSIS_STEPS.findIndex((step) => step.key === activeKey)
  return ANALYSIS_STEPS.map((step, index) => {
    const existing = steps?.find((item) => item.key === step.key)
    if (index < activeIndex) {
      return { ...step, status: 'done', detail: existing?.detail || '' }
    }
    if (index === activeIndex) {
      return { ...step, status: 'active', detail: String(message || '').trim() }
    }
    return { ...step, status: existing?.status === 'done' ? 'done' : 'pending', detail: existing?.detail || '' }
  })
}

export const completeAnalysisSteps = (steps) => ANALYSIS_STEPS.map((step) => ({
  ...step,
  status: 'done',
  detail: steps?.find((item) => item.key === step.key)?.detail || ''
}))
