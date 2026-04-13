<template>
  <div class="auth-shell register-page">
    <section class="auth-showcase register-showcase">
      <button
        class="secondary-button icon-button auth-theme-toggle"
        type="button"
        :title="themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
        @click="handleThemeToggle"
      >
        <span class="theme-toggle" aria-hidden="true"></span>
      </button>

      <div class="auth-hero">
        <div class="sidebar-brand hero-brand">
          <div class="brand-mark">AI</div>
          <div class="brand-copy">
            <p class="brand-eyebrow">Workspace Onboarding</p>
            <h2>创建一个可直接进入业务工作的账号。</h2>
          </div>
        </div>

        <p>
          新账号会继承统一的主题、会话存储和业务入口。注册完成后即可进入聊天、分析、
          库存和文档处理等核心模块。
        </p>

        <div class="auth-signal-row" aria-label="账号开通标签">
          <span class="signal-chip">Unified Session</span>
          <span class="signal-chip">Role Ready</span>
          <span class="signal-chip">Theme Memory</span>
        </div>

        <div class="auth-grid">
          <article class="auth-capability">
            <strong>统一会话</strong>
            <span>按账号维度保存聊天记录、分析配置和主题偏好。</span>
          </article>
          <article class="auth-capability">
            <strong>即时可用</strong>
            <span>注册成功后无需额外初始化，直接返回登录即可进入系统。</span>
          </article>
          <article class="auth-capability">
            <strong>轻量安全</strong>
            <span>密码校验最小 6 位，后续可在工作台内继续修改。</span>
          </article>
        </div>

        <div class="auth-ledger">
          <div class="auth-ledger-row">
            <span class="auth-ledger-label">开通后</span>
            <span class="auth-ledger-value">账号会立即具备统一主题、会话空间和业务入口，无需额外初始化。</span>
          </div>
          <div class="auth-ledger-row">
            <span class="auth-ledger-label">适用场景</span>
            <span class="auth-ledger-value">适合快速搭建运营协作入口，后续再按团队职责细化使用方式。</span>
          </div>
        </div>
      </div>

      <div class="auth-metric-row">
        <article class="auth-metric">
          <strong>设计统一</strong>
          <span>入口页与业务页共用同一套 token、组件、动效和深浅色语义。</span>
        </article>
        <article class="auth-metric">
          <strong>对接前端</strong>
          <span>布局遵循栅格与间距规范，便于后续继续扩展模块。</span>
        </article>
      </div>
    </section>

    <section class="auth-card register-card">
      <div class="auth-card-header">
        <p class="brand-eyebrow">Create Account</p>
        <h1>创建账号</h1>
        <p>填写基础信息，立即开通你的 WMS AI Workspace。</p>
      </div>

      <form class="auth-form" @submit.prevent="handleRegister">
        <label class="form-field">
          <span class="form-label">用户名</span>
          <input
            v-model="username"
            class="form-control"
            type="text"
            name="username"
            autocomplete="username"
            placeholder="建议使用英文用户名"
          />
        </label>
        <label class="form-field">
          <span class="form-label">密码</span>
          <input
            v-model="password"
            class="form-control"
            type="password"
            name="new-password"
            autocomplete="new-password"
            placeholder="至少 6 位"
          />
        </label>
        <label class="form-field">
          <span class="form-label">确认密码</span>
          <input
            v-model="confirmPassword"
            class="form-control"
            type="password"
            name="confirm-password"
            autocomplete="new-password"
            placeholder="再次输入密码"
            @keyup.enter="handleRegister"
          />
        </label>

        <button
          class="primary-button submit-btn"
          type="submit"
          :disabled="loading || !username || !password || !confirmPassword"
        >
          {{ loading ? '提交中...' : '创建账号' }}
        </button>

        <div v-if="errorMsg" class="error-banner" role="alert">{{ errorMsg }}</div>
        <div v-if="successMsg" class="success-banner" role="status">{{ successMsg }}</div>
      </form>

      <div class="auth-card-footer">
        <div class="auth-switch">
          <span>已经有账号？</span>
          <button class="ghost-button link-btn" type="button" @click="goLogin">返回登录</button>
        </div>
        <p class="footnote">注册后会保留你当前的主题偏好。</p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentTheme, toggleTheme } from '../utils/theme'

const router = useRouter()
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')
const themeMode = ref(getCurrentTheme())
const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'

const handleThemeToggle = () => {
  themeMode.value = toggleTheme()
}

const goLogin = () => {
  router.push('/login')
}

const handleRegister = async () => {
  const account = username.value.trim()
  if (!account || !password.value || !confirmPassword.value) {
    errorMsg.value = '请完整填写注册信息'
    successMsg.value = ''
    return
  }

  if (password.value.length < 6) {
    errorMsg.value = '密码至少需要 6 位'
    successMsg.value = ''
    return
  }

  if (password.value !== confirmPassword.value) {
    errorMsg.value = '两次输入的密码不一致'
    successMsg.value = ''
    return
  }

  loading.value = true
  errorMsg.value = ''
  successMsg.value = ''
  try {
    const res = await fetch(`${API_BASE}/api/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: account, password: password.value })
    })

    let data = null
    try {
      data = await res.json()
    } catch {
      data = null
    }

    if (res.ok && (!data || data.code === 201 || data.code === 200 || data.success === true)) {
      successMsg.value = '注册成功，请返回登录'
      return
    }

    errorMsg.value = data?.message || '注册失败'
  } catch {
    errorMsg.value = '注册接口不可用'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  align-items: stretch;
}

.register-showcase::before,
.register-card::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(circle at 80% 18%, rgba(37, 99, 235, 0.16), transparent 24%),
    radial-gradient(circle at 12% 88%, rgba(217, 119, 6, 0.12), transparent 22%);
}

.hero-brand h2 {
  font-size: clamp(34px, 4vw, 50px);
  line-height: 1.05;
  letter-spacing: -0.03em;
}

.register-card {
  gap: 24px;
}

.submit-btn {
  width: 100%;
  min-height: 48px;
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
</style>
