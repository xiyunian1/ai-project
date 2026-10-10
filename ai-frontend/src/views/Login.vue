<template>
  <div class="auth-shell login-page">
    <button
      class="secondary-button icon-button auth-theme-toggle"
      type="button"
      :title="themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
      @click="handleThemeToggle"
    >
      <span class="theme-toggle" aria-hidden="true"></span>
    </button>

    <div class="login-center-stack">
      <section class="auth-card login-card">
        <div class="auth-card-header">
          <p class="brand-eyebrow">账号入口</p>
          <h1>登录工作台</h1>
          <p>输入管理员账号密码，进入仓储智能工作台。</p>
        </div>

        <form class="auth-form" @submit.prevent="handleLogin">
          <label class="form-field">
            <span class="form-label">用户名</span>
            <input
              v-model="username"
              class="form-control"
              type="text"
              name="username"
              autocomplete="username"
              placeholder="请输入用户名"
            />
          </label>
          <label class="form-field">
            <span class="form-label">密码</span>
            <input
              v-model="password"
              class="form-control"
              type="password"
              name="current-password"
              autocomplete="current-password"
              placeholder="请输入密码"
              @keyup.enter="handleLogin"
            />
          </label>

          <button class="primary-button submit-btn" type="submit" :disabled="loading || !username || !password">
            {{ loading ? '登录中...' : '进入系统' }}
          </button>

          <div v-if="errorMsg" class="error-banner" role="alert">{{ errorMsg }}</div>
        </form>

        <div class="auth-card-footer">
          <div class="auth-switch">
            <span>账号由管理员在“用户管理”中创建。</span>
          </div>
          <p class="footnote">© 2026 WMS AI Workspace</p>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { setAuthSession } from '../utils/auth'
import { buildApiUrl, parseJsonSafe } from '../utils/api'
import { getCurrentTheme, toggleTheme } from '../utils/theme'

const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMsg = ref('')
const themeMode = ref(getCurrentTheme())

const handleThemeToggle = () => {
  themeMode.value = toggleTheme()
}

const handleLogin = async () => {
  if (!username.value || !password.value) {
    errorMsg.value = '请输入账号和密码'
    return
  }

  loading.value = true
  errorMsg.value = ''
  try {
    const account = username.value.trim()
    const res = await fetch(buildApiUrl('/api/auth/login'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: account, password: password.value })
    })

    const data = await parseJsonSafe(res)
    if (res.ok && data?.code === 200 && data?.token) {
      setAuthSession({
        token: data.token,
        expiresAt: data.expiresAt,
        username: data.username,
        account: data.account || account,
        role: data.role || 'USER'
      })
      router.push('/chat')
      return
    }

    errorMsg.value = data?.message || '登录失败'
  } catch {
    errorMsg.value = '后端服务不可用'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  place-items: center;
  padding: 24px;
}

.login-page::before,
.login-page::after {
  content: '';
  position: absolute;
  pointer-events: none;
}

.login-page::before {
  inset: 24px;
  border-radius: 28px;
  border: 1px solid rgba(79, 123, 255, 0.08);
  background:
    radial-gradient(circle at 50% 18%, rgba(79, 123, 255, 0.08), transparent 24%),
    radial-gradient(circle at 50% 100%, rgba(30, 168, 153, 0.05), transparent 28%);
}

.login-page::after {
  left: 50%;
  top: 24px;
  bottom: 24px;
  width: 1px;
  background: linear-gradient(180deg, transparent, rgba(79, 123, 255, 0.12), transparent);
  transform: translateX(-50%);
}

.login-center-stack {
  position: relative;
  z-index: 1;
  width: min(100%, 480px);
  display: grid;
  gap: 0;
  justify-items: center;
}

.login-card {
  position: relative;
  width: min(100%, 480px);
  min-height: auto;
  justify-self: center;
}

.auth-card-header {
  display: grid;
  gap: 8px;
  text-align: center;
}

.auth-card-header h1,
.auth-card-header p {
  margin: 0;
}

.auth-card-header p:last-child {
  max-width: 320px;
  justify-self: center;
  color: var(--text-secondary);
}

.submit-btn {
  width: 100%;
  min-height: 50px;
  font-size: 15px;
}

.link-btn {
  min-height: auto;
  padding: 0;
}

.footnote {
  margin: 0;
  text-align: center;
  font-size: 12px;
  color: var(--text-tertiary);
}

@media (max-width: 820px) {
  .login-page {
    padding: 18px;
  }

  .login-page::before {
    inset: 16px;
  }
}

@media (max-width: 640px) {
  .login-page {
    padding: 14px;
  }

  .login-page::before {
    inset: 12px;
    border-radius: 20px;
  }

  .login-page::after {
    display: none;
  }

  .login-center-stack {
    width: min(100%, 460px);
  }
}
</style>
