<template>
  <div class="workspace-shell account-shell">
    <WorkspaceSidebar
      eyebrow="账号设置"
      title="账号设置"
      description="管理当前账号、界面模式和登录状态。"
      mark="USER"
      :active-feature="activeFeature"
      @feature-select="selectFeature"
    />

    <main class="workspace-main account-main">
      <header class="ops-header surface-panel is-hero">
        <div class="ops-header-copy">
          <p class="brand-eyebrow">账号控制台</p>
          <h1>账号设置</h1>
          <p>集中处理账号信息、密码修改和退出登录，不再占用智能对话工作区。</p>
          <div class="header-inline-meta">
            <span class="header-annotation">当前用户</span>
            <span class="header-dot"></span>
            <span class="header-annotation">密码安全</span>
            <span class="header-dot"></span>
            <span class="header-annotation">主题偏好</span>
          </div>
        </div>
        <div class="ops-header-actions">
          <button class="secondary-button" type="button" @click="goChat">返回智能对话</button>
          <button
            class="secondary-button icon-button"
            type="button"
            :title="themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
            @click="handleThemeToggle"
          >
            <span class="theme-toggle" aria-hidden="true"></span>
          </button>
        </div>
      </header>

      <section class="account-grid">
        <article class="surface-panel account-card account-identity-card">
          <div class="account-avatar">{{ userInitial }}</div>
          <div>
            <p class="brand-eyebrow">当前登录</p>
            <h2>{{ userName }}</h2>
            <p>{{ currentAccount }}</p>
          </div>
          <button type="button" class="danger-button" @click="logout">退出登录</button>
        </article>

        <article class="surface-panel account-card">
          <div class="panel-head">
            <div>
              <p class="brand-eyebrow">界面模式</p>
              <h2>界面模式</h2>
              <p class="panel-note">支持浅色和深色模式切换，当前设置会按账号保留。</p>
            </div>
            <span class="status-pill is-brand">{{ themeMode === 'dark' ? '深色' : '浅色' }}</span>
          </div>
          <button type="button" class="secondary-button" @click="handleThemeToggle">
            {{ themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式' }}
          </button>
        </article>

        <article class="surface-panel account-card account-password-card">
          <div class="panel-head">
            <div>
              <p class="brand-eyebrow">密码管理</p>
              <h2>修改密码</h2>
              <p class="panel-note">新密码至少 6 位，保存成功后继续保持当前登录状态。</p>
            </div>
          </div>

          <div class="account-form-grid">
            <label>
              <span>当前密码</span>
              <input v-model="passwordForm.current" type="password" placeholder="当前密码" />
            </label>
            <label>
              <span>新密码</span>
              <input v-model="passwordForm.next" type="password" placeholder="新密码（至少 6 位）" />
            </label>
            <label>
              <span>确认新密码</span>
              <input
                v-model="passwordForm.confirm"
                type="password"
                placeholder="确认新密码"
                @keyup.enter="handleChangePassword"
              />
            </label>
          </div>

          <p v-if="passwordError" class="error-banner">{{ passwordError }}</p>
          <p v-if="passwordSuccess" class="success-banner">{{ passwordSuccess }}</p>

          <div class="account-actions">
            <button class="primary-button" type="button" :disabled="passwordLoading" @click="handleChangePassword">
              {{ passwordLoading ? '保存中...' : '保存密码' }}
            </button>
            <button class="secondary-button" type="button" :disabled="passwordLoading" @click="resetPasswordState">清空输入</button>
          </div>
        </article>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkspaceSidebar from '../components/WorkspaceSidebar.vue'
import {
  ACCOUNT_KEY,
  NAME_KEY,
  clearAuthSession,
  getToken,
  isTokenValid,
  resolveAccountKey,
  withAuthHeaders
} from '../utils/auth'
import { buildApiUrl, parseJsonSafe } from '../utils/api'
import { getCurrentTheme, toggleTheme } from '../utils/theme'
import { getWorkspaceFeatureKeyByPath } from '../utils/workspace'

const router = useRouter()
const route = useRoute()
const themeMode = ref(getCurrentTheme())
const activeFeature = computed(() => getWorkspaceFeatureKeyByPath(route.path))

const currentAccount = ref(resolveAccountKey({
  account: localStorage.getItem(ACCOUNT_KEY),
  token: getToken(),
  name: localStorage.getItem(NAME_KEY)
}))
const userName = ref(localStorage.getItem(NAME_KEY) || currentAccount.value || '用户')
const userInitial = computed(() => (userName.value || 'U').charAt(0).toUpperCase())

const passwordLoading = ref(false)
const passwordError = ref('')
const passwordSuccess = ref('')
const passwordForm = reactive({
  current: '',
  next: '',
  confirm: ''
})

const handleThemeToggle = () => {
  themeMode.value = toggleTheme()
}

const selectFeature = (feature) => {
  if (!feature?.route || feature.route === route.path) return
  router.push(feature.route)
}

const goChat = () => router.push('/chat')

const clearPasswordFields = () => {
  passwordForm.current = ''
  passwordForm.next = ''
  passwordForm.confirm = ''
}

const resetPasswordState = () => {
  clearPasswordFields()
  passwordError.value = ''
  passwordSuccess.value = ''
  passwordLoading.value = false
}

const forceLogout = (message = '登录状态已失效，请重新登录') => {
  clearAuthSession()
  if (message) window.alert(message)
  router.replace('/login')
}

const ensureAuthorized = () => {
  if (isTokenValid()) return true
  forceLogout('登录状态已过期，请重新登录')
  return false
}

const handleChangePassword = async () => {
  if (!ensureAuthorized()) return
  if (!passwordForm.current || !passwordForm.next || !passwordForm.confirm) {
    passwordError.value = '请完整填写密码信息'
    passwordSuccess.value = ''
    return
  }
  if (passwordForm.next.length < 6) {
    passwordError.value = '新密码至少 6 位'
    passwordSuccess.value = ''
    return
  }
  if (passwordForm.next !== passwordForm.confirm) {
    passwordError.value = '两次输入的密码不一致'
    passwordSuccess.value = ''
    return
  }

  passwordLoading.value = true
  passwordError.value = ''
  passwordSuccess.value = ''
  try {
    const res = await fetch(buildApiUrl('/api/auth/change-password'), {
      method: 'POST',
      headers: withAuthHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({
        currentPassword: passwordForm.current,
        newPassword: passwordForm.next
      })
    })

    if (res.status === 401) {
      forceLogout()
      return
    }

    const data = await parseJsonSafe(res)
    if (res.ok && (!data || data.code === 200 || data.success === true)) {
      passwordSuccess.value = '密码更新成功'
      clearPasswordFields()
      return
    }
    passwordError.value = data?.message || '修改失败'
  } catch {
    passwordError.value = '修改密码失败，请检查后端服务'
  } finally {
    passwordLoading.value = false
  }
}

const logout = () => {
  if (!window.confirm('确定退出登录吗？')) return
  clearAuthSession()
  router.replace('/login')
}
</script>

<style scoped>
.account-main {
  display: grid;
  gap: 20px;
  min-width: 0;
}

.ops-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding: 28px 30px;
}

.ops-header-copy h1,
.ops-header-copy p {
  margin: 0;
}

.ops-header-copy h1 {
  font-size: clamp(30px, 3.2vw, 40px);
  line-height: 1.12;
}

.ops-header-copy p:last-child {
  margin-top: 10px;
  font-size: 14px;
  color: var(--text-secondary);
}

.ops-header-actions,
.header-inline-meta,
.account-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.header-inline-meta {
  margin-top: 12px;
}

.header-annotation {
  font-family: var(--font-label);
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--text-tertiary);
}

.header-dot {
  width: 4px;
  height: 4px;
  border-radius: 999px;
  background: rgba(127, 145, 184, 0.7);
}

.account-grid {
  display: grid;
  grid-template-columns: minmax(280px, 0.9fr) minmax(320px, 1.1fr);
  gap: 20px;
}

.account-card {
  display: grid;
  gap: 18px;
  padding: 26px;
  align-content: start;
}

.account-identity-card {
  align-content: center;
  justify-items: start;
  min-height: 300px;
  background:
    radial-gradient(circle at top right, rgba(15, 159, 141, 0.14), transparent 32%),
    var(--bg-surface-1);
}

.account-avatar {
  width: 76px;
  height: 76px;
  display: grid;
  place-items: center;
  border-radius: 24px;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-500), var(--teal-500));
  box-shadow: 0 20px 44px rgba(38, 68, 152, 0.24);
  font-family: var(--font-label);
  font-size: 26px;
  font-weight: 800;
}

.account-identity-card h2,
.account-identity-card p {
  margin: 0;
}

.account-identity-card h2 {
  font-size: 30px;
}

.account-identity-card p:not(.brand-eyebrow) {
  margin-top: 6px;
  color: var(--text-secondary);
}

.account-password-card {
  grid-row: span 2;
}

.account-form-grid {
  display: grid;
  gap: 14px;
}

.account-form-grid label {
  display: grid;
  gap: 8px;
}

.account-form-grid span {
  font-size: 13px;
  color: var(--text-secondary);
  font-weight: 700;
}

.account-form-grid input {
  min-height: 42px;
  padding: 0 12px;
}

.danger-button {
  min-height: 42px;
  padding: 0 16px;
  border: 1px solid rgba(220, 38, 38, 0.28);
  border-radius: 14px;
  color: #dc2626;
  background: rgba(220, 38, 38, 0.08);
  cursor: pointer;
  font-weight: 700;
}

@media (max-width: 980px) {
  .account-grid {
    grid-template-columns: 1fr;
  }

  .ops-header {
    flex-direction: column;
  }
}
</style>
