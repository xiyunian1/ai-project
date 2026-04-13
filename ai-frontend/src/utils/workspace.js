export const WORKSPACE_FEATURES = [
  { key: 'chat', label: '智能对话', desc: '对话式工作台', route: '/chat', badge: 'AI' },
  { key: 'analysis', label: '分析助手', desc: '对话式数据分析', route: '/analysis', badge: 'BI' },
  { key: 'inventory', label: '库存洞察', desc: '库存与预警分析', route: '/inventory', badge: 'INV' },
  { key: 'workflow', label: '流程助手', desc: '入库出库流程', route: '/workflow', badge: 'OPS' },
  { key: 'document', label: '文档处理', desc: '上传文档并按要求处理', route: '/document-process', badge: 'DOC' }
]

export const getWorkspaceFeatureKeyByPath = (path = '') => {
  if (path.startsWith('/analysis')) return 'analysis'
  if (path.startsWith('/inventory')) return 'inventory'
  if (path.startsWith('/workflow')) return 'workflow'
  if (path.startsWith('/document-process')) return 'document'
  return 'chat'
}

export const getWorkspaceFeatureBadge = (key = '') => {
  return WORKSPACE_FEATURES.find((item) => item.key === key)?.badge || 'APP'
}
