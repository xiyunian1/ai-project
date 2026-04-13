<template>
  <div class="auth-shell login-page">
    <section class="auth-showcase login-showcase">
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
            <p class="brand-eyebrow">WMS AI Workspace</p>
            <h2>一个更安静、更清晰、更可信的仓储智能工作台。</h2>
          </div>
        </div>

        <p>
          把智能问答、库存洞察、流程指导和文档处理整合到统一工作区中，减少界面切换和信息噪声，
          让每一步操作都更接近可执行结果。
        </p>

        <div class="auth-signal-row" aria-label="工作台能力标签">
          <span class="signal-chip">Dispatch Layer</span>
          <span class="signal-chip">Inventory Watch</span>
          <span class="signal-chip">Document Loop</span>
        </div>

        <div class="auth-grid">
          <article class="auth-capability">
            <strong>智能问答</strong>
            <span>支持文本、图片和数据库问答，适合日常运营场景。</span>
          </article>
          <article class="auth-capability">
            <strong>库存洞察</strong>
            <span>快速查看低库存、SKU 检索结果和风险提示。</span>
          </article>
          <article class="auth-capability">
            <strong>文档处理</strong>
            <span>上传文档并按要求处理，保留结果预览与下载闭环。</span>
          </article>
        </div>

        <div class="auth-ledger">
          <div class="auth-ledger-row">
            <span class="auth-ledger-label">工作线</span>
            <span class="auth-ledger-value">对话、分析、库存、流程、文档在同一工作区闭环推进。</span>
          </div>
          <div class="auth-ledger-row">
            <span class="auth-ledger-label">操作感</span>
            <span class="auth-ledger-value">以低干扰排版、清晰状态和直接动作替代传统后台的拥挤信息墙。</span>
          </div>
        </div>
      </div>

      <div class="auth-metric-row">
        <article class="auth-metric">
          <strong>多能力协作</strong>
          <span>对话、分析、库存、流程和文档同一套视觉骨架。</span>
        </article>
        <article class="auth-metric">
          <strong>低干扰体验</strong>
          <span>统一层级、轻动效和明确状态，避免表单式廉价感。</span>
        </article>
      </div>
    </section>

    <section class="auth-card login-card">
      <div class="auth-card-header">
        <p class="brand-eyebrow">账号入口</p>
        <h1>登录工作台</h1>
        <p>输入账号密码，继续当前会话和配置。</p>
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
          <span>还没有账号？</span>
          <button class="ghost-button link-btn" type="button" @click="goRegister">立即注册</button>
        </div>
        <p class="footnote">© 2026 WMS AI Workspace</p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { setAuthSession } from '../utils/auth'
import { getCurrentTheme, toggleTheme } from '../utils/theme'

const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMsg = ref('')
const themeMode = ref(getCurrentTheme())
const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'

const handleThemeToggle = () => {
  themeMode.value = toggleTheme()
}

const goRegister = () => {
  router.push('/register')
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
    const res = await fetch(`${API_BASE}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: account, password: password.value })
    })

    const data = await res.json().catch(() => null)
    if (res.ok && data?.code === 200 && data?.token) {
      setAuthSession({
        token: data.token,
        expiresAt: data.expiresAt,
        username: data.username,
        account: data.account || account
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
  align-items: stretch;
}

.login-showcase::before,
.login-card::before {
  content: '';
  position: absolute;
  inset: auto auto 0 0;
  width: 320px;
  height: 320px;
  border-radius: 999px;
  background: radial-gradient(circle, rgba(37, 99, 235, 0.18), transparent 70%);
  transform: translate(-18%, 26%);
  pointer-events: none;
}

.login-showcase::after {
  content: '';
  position: absolute;
  top: -80px;
  right: -60px;
  width: 260px;
  height: 260px;
  border-radius: 999px;
  background: radial-gradient(circle, rgba(15, 159, 141, 0.18), transparent 70%);
  pointer-events: none;
}

.hero-brand {
  align-items: flex-start;
}

.hero-brand h2 {
  font-size: clamp(34px, 4vw, 52px);
  line-height: 1.04;
  letter-spacing: -0.03em;
}

.login-card {
  gap: 26px;
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

@media (max-width: 820px) {
  .login-page {
    padding-top: 16px;
  }
}
</style>
