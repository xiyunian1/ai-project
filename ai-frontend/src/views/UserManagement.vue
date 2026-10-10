<template>
  <div class="workspace-shell user-management-shell">
      <WorkspaceSidebar
        eyebrow="用户管理"
        title="用户管理"
        description="默认管理员账号与演示用户管理。"
      mark="ADM"
      :active-feature="activeFeature"
      @feature-select="selectFeature"
    />

    <main class="workspace-main user-management-main">
      <header class="ops-header surface-panel is-hero">
        <div class="ops-header-copy">
          <p class="brand-eyebrow">用户管理</p>
          <h1>用户管理</h1>
          <p>当前系统保留一个默认管理员账号；需要演示多用户时，可在此创建普通账号并管理状态。</p>
          <div class="header-inline-meta">
            <span class="header-annotation">管理员专属</span>
            <span class="header-dot"></span>
            <span class="header-annotation">账号生命周期</span>
            <span class="header-dot"></span>
            <span class="header-annotation">角色权限</span>
          </div>
        </div>
        <div class="ops-header-actions">
          <button class="secondary-button" type="button" @click="loadUsers">刷新列表</button>
        </div>
      </header>

      <section class="admin-grid">
        <article class="surface-panel admin-card create-card">
          <div class="panel-head">
            <div>
              <p class="brand-eyebrow">新增账号</p>
              <h2>新增账号</h2>
              <p class="panel-note">毕业设计演示默认只保留管理员账号，如需测试权限隔离可临时创建用户。</p>
            </div>
          </div>

          <div class="admin-form-grid">
            <label>
              <span>账号</span>
              <input v-model.trim="createForm.username" type="text" placeholder="username" />
            </label>
            <label>
              <span>显示名</span>
              <input v-model.trim="createForm.displayName" type="text" placeholder="显示名（可选）" />
            </label>
            <label>
              <span>初始密码</span>
              <input v-model="createForm.password" type="password" placeholder="至少 6 位" />
            </label>
            <label>
              <span>角色</span>
              <select v-model="createForm.role">
                <option value="USER">普通用户</option>
                <option value="ADMIN">管理员</option>
              </select>
            </label>
          </div>

          <p v-if="message" :class="messageType === 'error' ? 'error-banner' : 'success-banner'">{{ message }}</p>

          <div class="admin-actions">
            <button class="primary-button" type="button" :disabled="loading" @click="createUser">
              {{ loading ? '处理中...' : '创建账号' }}
            </button>
            <button class="secondary-button" type="button" @click="resetCreateForm">清空</button>
          </div>
        </article>

        <article class="surface-panel admin-card users-card">
          <div class="panel-head">
            <div>
              <p class="brand-eyebrow">账号列表</p>
              <h2>账号列表</h2>
              <p class="panel-note">当前列表默认只有管理员账号；临时创建的测试账号可在演示后删除。</p>
            </div>
            <span class="status-pill is-brand">{{ users.length }} 个账号</span>
          </div>

          <div v-if="listLoading" class="empty-state">正在加载账号...</div>
          <div v-else-if="!users.length" class="empty-state">暂无账号</div>
          <div v-else class="user-table-wrap">
            <table class="user-table">
              <thead>
                <tr>
                  <th>账号</th>
                  <th>显示名</th>
                  <th>角色</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="user in users" :key="user.id">
                  <td>
                    <strong>{{ user.username }}</strong>
                    <small>ID {{ user.id }}</small>
                  </td>
                  <td>
                    <input v-model.trim="user.displayName" type="text" />
                  </td>
                  <td>
                    <select v-model="user.role">
                      <option value="USER">普通用户</option>
                      <option value="ADMIN">管理员</option>
                    </select>
                  </td>
                  <td>
                    <span :class="['status-pill', user.enabled ? 'is-brand' : 'is-muted']">
                      {{ user.enabled ? '启用' : '禁用' }}
                    </span>
                  </td>
                  <td class="row-actions">
                    <button class="secondary-button" type="button" @click="saveUser(user)">保存</button>
                    <button class="secondary-button" type="button" @click="toggleUser(user)">
                      {{ user.enabled ? '禁用' : '启用' }}
                    </button>
                    <button class="secondary-button" type="button" @click="resetPassword(user)">重置密码</button>
                    <button class="danger-button" type="button" @click="deleteUser(user)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkspaceSidebar from '../components/WorkspaceSidebar.vue'
import { clearAuthSession, isAdminRole, isTokenValid, withAuthHeaders } from '../utils/auth'
import { buildApiUrl, parseJsonSafe } from '../utils/api'
import { getWorkspaceFeatureKeyByPath } from '../utils/workspace'

const router = useRouter()
const route = useRoute()
const activeFeature = computed(() => getWorkspaceFeatureKeyByPath(route.path))

const users = ref([])
const loading = ref(false)
const listLoading = ref(false)
const message = ref('')
const messageType = ref('success')

const createForm = reactive({
  username: '',
  displayName: '',
  password: '',
  role: 'USER'
})

const selectFeature = (feature) => {
  if (!feature?.route || feature.route === route.path) return
  router.push(feature.route)
}

const showMessage = (type, text) => {
  messageType.value = type
  message.value = text
}

const forceLogout = () => {
  clearAuthSession()
  router.replace('/login')
}

const ensureAdmin = () => {
  if (!isTokenValid()) {
    forceLogout()
    return false
  }
  if (!isAdminRole()) {
    router.replace('/chat')
    return false
  }
  return true
}

const handleAuthStatus = (status) => {
  if (status === 401) {
    forceLogout()
    return true
  }
  if (status === 403) {
    router.replace('/chat')
    return true
  }
  return false
}

const requestJson = async (path, options = {}) => {
  const res = await fetch(buildApiUrl(path), {
    ...options,
    headers: withAuthHeaders({
      'Content-Type': 'application/json',
      ...(options.headers || {})
    })
  })
  if (handleAuthStatus(res.status)) return { res, data: null }
  const data = await parseJsonSafe(res)
  if (!res.ok) {
    throw new Error(data?.message || '操作失败')
  }
  return { res, data }
}

const loadUsers = async () => {
  if (!ensureAdmin()) return
  listLoading.value = true
  try {
    const { data } = await requestJson('/api/admin/users')
    users.value = Array.isArray(data) ? data : []
  } catch (err) {
    showMessage('error', err?.message || '加载账号失败')
  } finally {
    listLoading.value = false
  }
}

const resetCreateForm = () => {
  createForm.username = ''
  createForm.displayName = ''
  createForm.password = ''
  createForm.role = 'USER'
}

const createUser = async () => {
  if (!ensureAdmin()) return
  if (!createForm.username || !createForm.password) {
    showMessage('error', '请填写账号和初始密码')
    return
  }
  if (createForm.password.length < 6) {
    showMessage('error', '初始密码至少 6 位')
    return
  }
  loading.value = true
  try {
    await requestJson('/api/admin/users', {
      method: 'POST',
      body: JSON.stringify(createForm)
    })
    showMessage('success', '账号已创建')
    resetCreateForm()
    await loadUsers()
  } catch (err) {
    showMessage('error', err?.message || '创建账号失败')
  } finally {
    loading.value = false
  }
}

const saveUser = async (user) => {
  try {
    const { data } = await requestJson(`/api/admin/users/${user.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ displayName: user.displayName, role: user.role })
    })
    Object.assign(user, data)
    showMessage('success', '账号信息已保存')
  } catch (err) {
    showMessage('error', err?.message || '保存失败')
  }
}

const toggleUser = async (user) => {
  try {
    const { data } = await requestJson(`/api/admin/users/${user.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ enabled: !user.enabled })
    })
    Object.assign(user, data)
    showMessage('success', user.enabled ? '账号已启用' : '账号已禁用')
  } catch (err) {
    showMessage('error', err?.message || '状态更新失败')
  }
}

const resetPassword = async (user) => {
  const password = window.prompt(`请输入 ${user.username} 的新密码（至少 6 位）`)
  if (password === null) return
  if (password.length < 6) {
    showMessage('error', '新密码至少 6 位')
    return
  }
  try {
    await requestJson(`/api/admin/users/${user.id}/reset-password`, {
      method: 'POST',
      body: JSON.stringify({ password })
    })
    showMessage('success', '密码已重置')
  } catch (err) {
    showMessage('error', err?.message || '重置密码失败')
  }
}

const deleteUser = async (user) => {
  if (!window.confirm(`确认永久删除账号 ${user.username}？此操作不可恢复。`)) return
  try {
    await requestJson(`/api/admin/users/${user.id}`, { method: 'DELETE' })
    users.value = users.value.filter((item) => item.id !== user.id)
    showMessage('success', '账号已删除')
  } catch (err) {
    showMessage('error', err?.message || '删除失败')
  }
}

onMounted(loadUsers)
</script>

<style scoped>
.user-management-main {
  display: grid;
  gap: 20px;
  min-width: 0;
}

.admin-grid {
  display: grid;
  grid-template-columns: minmax(320px, 0.85fr) minmax(0, 1.6fr);
  gap: 20px;
  align-items: start;
}

.admin-card {
  padding: 26px;
}

.admin-form-grid {
  display: grid;
  gap: 14px;
}

.admin-form-grid label {
  display: grid;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 13px;
}

.admin-form-grid input,
.admin-form-grid select,
.user-table input,
.user-table select {
  width: 100%;
  min-height: 42px;
  border: 1px solid var(--border-subtle);
  border-radius: 14px;
  padding: 0 12px;
  color: var(--text-primary);
  background: var(--bg-surface-2);
}

.admin-actions,
.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 16px;
}

.user-table-wrap {
  overflow-x: auto;
}

.user-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 780px;
}

.user-table th,
.user-table td {
  padding: 14px 10px;
  border-bottom: 1px solid var(--border-subtle);
  text-align: left;
  vertical-align: middle;
}

.user-table th {
  color: var(--text-tertiary);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.user-table td strong,
.user-table td small {
  display: block;
}

.user-table td small {
  margin-top: 4px;
  color: var(--text-tertiary);
}

.empty-state {
  padding: 28px;
  color: var(--text-secondary);
  text-align: center;
}

.status-pill.is-muted {
  background: var(--bg-surface-2);
  color: var(--text-tertiary);
}

@media (max-width: 980px) {
  .admin-grid {
    grid-template-columns: 1fr;
  }
}
</style>
