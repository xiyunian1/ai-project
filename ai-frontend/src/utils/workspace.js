export const WORKSPACE_FEATURES = [
  { key: 'chat', label: '智能对话', desc: '仓储业务问答入口', route: '/chat', badge: 'AI' },
  { key: 'analysis', label: '分析助手', desc: 'SQL 数据分析与结论生成', route: '/analysis', badge: 'BI' },
  { key: 'inventory', label: '库存洞察', desc: '库存风险看板与处置联动', route: '/inventory', badge: 'INV' },
  { key: 'knowledge', label: '资料管理', desc: '业务资料知识库维护', route: '/knowledge', badge: 'KB' },
  { key: 'document', label: '文档处理', desc: '临时文档提取与整理', route: '/document-process', badge: 'DOC' },
  { key: 'settings', label: '系统配置', desc: '模型与数据库连接配置', route: '/settings', badge: 'CFG' },
  { key: 'admin-users', label: '用户管理', desc: '管理员账号与权限控制', route: '/admin/users', badge: 'ADM', adminOnly: true },
  { key: 'account', label: '账号设置', desc: '密码与登录状态管理', route: '/account', badge: 'USER' }
]

export const getWorkspaceFeaturesForRole = (role = 'USER') => {
  const isAdmin = String(role || '').toUpperCase() === 'ADMIN'
  return WORKSPACE_FEATURES.filter((feature) => !feature.adminOnly || isAdmin)
}

export const getWorkspaceFeatureKeyByPath = (path = '') => {
  if (path.startsWith('/admin/users')) return 'admin-users'
  if (path.startsWith('/analysis')) return 'analysis'
  if (path.startsWith('/inventory')) return 'inventory'
  if (path.startsWith('/knowledge')) return 'knowledge'
  if (path.startsWith('/document-process')) return 'document'
  if (path.startsWith('/settings')) return 'settings'
  if (path.startsWith('/account')) return 'account'
  return 'chat'
}

export const getWorkspaceFeatureBadge = (key = '') => {
  return WORKSPACE_FEATURES.find((item) => item.key === key)?.badge || 'APP'
}
