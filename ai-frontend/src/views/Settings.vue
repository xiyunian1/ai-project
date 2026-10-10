<template>
  <div class="workspace-shell settings-shell">
    <WorkspaceSidebar
      eyebrow="系统设置"
      title="系统配置"
      description="统一维护 SQL 数据库连接和模型调用配置。"
      mark="CFG"
      :active-feature="activeFeature"
      @feature-select="selectFeature"
    />

    <main class="workspace-main settings-main">
      <header class="ops-header surface-panel is-hero">
        <div class="ops-header-copy">
          <p class="brand-eyebrow">配置中心</p>
          <h1>系统配置</h1>
          <p>把 SQL 数据库连接和模型参数集中管理，分析页只负责使用已保存配置。</p>
          <div class="header-inline-meta">
            <span class="header-annotation">SQL 数据库</span>
            <span class="header-dot"></span>
            <span class="header-annotation">兼容模型接口</span>
            <span class="header-dot"></span>
            <span class="header-annotation">本地演示配置</span>
          </div>
        </div>
        <div class="ops-header-actions">
          <button class="secondary-button" type="button" @click="goAnalysis">返回分析助手</button>
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

      <section class="settings-grid">
        <article class="surface-panel settings-card">
          <div class="panel-head">
            <div>
              <p class="brand-eyebrow">数据库配置</p>
              <h2>SQL 数据库配置</h2>
              <p class="panel-note">分析助手使用这里保存的真实数据库连接配置。密码不会写入本地持久化配置。</p>
            </div>
            <span class="status-pill is-brand">真实数据库</span>
          </div>

          <div class="setting-section-title">
            <span>1</span>
            <strong>连接参数</strong>
          </div>
          <div class="settings-form-grid">
            <label>
              <span>服务器</span>
              <input v-model.trim="dbConfig.server" type="text" placeholder="localhost" />
            </label>
            <label>
              <span>端口</span>
              <input v-model.number="dbConfig.port" type="number" min="1" max="65535" placeholder="1433" />
            </label>
            <label>
              <span>数据库名</span>
              <input v-model.trim="dbConfig.database" type="text" placeholder="myai" />
            </label>
            <label>
              <span>用户名</span>
              <input v-model.trim="dbConfig.username" type="text" placeholder="myai_user" />
            </label>
            <label>
              <span>密码</span>
              <input v-model="dbConfig.password" type="password" placeholder="仅当前页面使用" />
            </label>
            <label>
              <span>JDBC URL（可选）</span>
              <input v-model.trim="dbConfig.jdbcUrl" type="text" placeholder="jdbc:jtds:sqlserver://localhost:1433/myai" />
            </label>
            <label class="settings-full-row">
              <span>允许的表（逗号分隔）</span>
              <input v-model.trim="dbConfig.allowedTables" type="text" placeholder="dbo.inout" />
            </label>
          </div>

          <div class="setting-toggle-card slim">
            <label class="toggle-inline">
              <input v-model="dbConfig.discoverTables" type="checkbox" />
              <span>当允许表为空时自动发现数据库中的表</span>
            </label>
            <div class="setting-explain compact">
              <p>如果“允许的表”没有填写，勾选后系统会尝试读取数据库里的表名；不勾选则只使用后端预设的允许表。</p>
            </div>
          </div>

          <p v-if="dbMessage" :class="dbMessageType === 'error' ? 'error-banner' : 'success-banner'">{{ dbMessage }}</p>

          <div class="settings-actions setting-action-row">
            <button class="primary-button" type="button" @click="saveDatabaseConfig">保存数据库配置</button>
            <button class="secondary-button" type="button" @click="resetDatabaseConfig">重置数据库配置</button>
          </div>
        </article>

        <article class="surface-panel settings-card">
          <div class="panel-head">
            <div>
              <p class="brand-eyebrow">模型配置</p>
              <h2>模型配置</h2>
              <p class="panel-note">模型配置会保存到本机浏览器，下次打开会自动带出；请勿在公共电脑保存真实密钥。</p>
            </div>
            <span class="status-pill is-brand">真实模型</span>
          </div>

          <div class="warning-banner">
            该区域允许保存 API Key，适合本机毕业设计演示；本页保存配置优先于后端环境变量。不要在公共电脑或生产环境保存真实密钥。
          </div>

          <div class="setting-section-title">
            <span>1</span>
            <strong>模型参数</strong>
          </div>
          <div class="settings-form-grid">
            <label>
              <span>模型提供方</span>
              <select v-model="modelConfig.provider">
                <option value="openai-compatible">openai-compatible</option>
                <option value="deepseek">deepseek</option>
              </select>
            </label>
            <label>
              <span>接口地址</span>
              <input v-model.trim="modelConfig.baseUrl" type="text" placeholder="https://api.deepseek.com" />
            </label>
            <label>
              <span>API 密钥</span>
              <input v-model="modelConfig.apiKey" type="password" placeholder="sk-..." />
            </label>
            <label>
              <span>模型名称</span>
              <input v-model.trim="modelConfig.model" type="text" placeholder="如：deepseek-chat / qwen-plus / gpt-4o-mini" />
            </label>
            <label v-if="availableModels.length" class="settings-full-row">
              <span>可用模型</span>
              <select v-model="modelConfig.model">
                <option v-for="model in availableModels" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
          </div>

          <p v-if="modelMessage" :class="modelMessageType === 'error' ? 'error-banner' : 'success-banner'">{{ modelMessage }}</p>

          <div class="settings-actions setting-action-row">
            <button class="secondary-button" type="button" :disabled="modelLoading" @click="loadModels">
              {{ modelLoading ? '获取中...' : '获取模型' }}
            </button>
            <button class="primary-button" type="button" @click="saveModelConfig">保存模型配置</button>
            <button class="secondary-button" type="button" @click="resetModelConfig">清空模型配置</button>
          </div>
        </article>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkspaceSidebar from '../components/WorkspaceSidebar.vue'
import { ACCOUNT_KEY, NAME_KEY, getToken, resolveAccountKey } from '../utils/auth'
import { buildApiUrl, parseJsonSafe } from '../utils/api'
import {
  cloneDbConfig,
  createDefaultDbConfig,
  loadDbConfigFromStorage,
  toStoredDbConfig
} from '../utils/analysisDbConfig'
import {
  clearModelConfigFromLocal,
  clearModelConfigFromSession,
  createDefaultModelConfig,
  loadModelConfigFromLocal,
  saveModelConfigToLocal,
  saveModelConfigToSession
} from '../utils/modelConfig'
import { getCurrentTheme, toggleTheme } from '../utils/theme'
import { getWorkspaceFeatureKeyByPath } from '../utils/workspace'

const DB_CONFIG_STORAGE_PREFIX = 'WMS_ANALYSIS_DB_CONFIG'

const router = useRouter()
const route = useRoute()
const themeMode = ref(getCurrentTheme())
const activeFeature = computed(() => getWorkspaceFeatureKeyByPath(route.path))

const currentAccount = resolveAccountKey({
  account: localStorage.getItem(ACCOUNT_KEY),
  token: getToken(),
  name: localStorage.getItem(NAME_KEY)
})

const dbStorageKey = `${DB_CONFIG_STORAGE_PREFIX}_${currentAccount}`
const dbConfig = ref(loadDbConfigFromStorage(localStorage, dbStorageKey))
const dbMessage = ref('')
const dbMessageType = ref('success')

const modelConfig = ref(loadModelConfigFromLocal(localStorage))
const modelMessage = ref('')
const modelMessageType = ref('success')
const modelLoading = ref(false)
const availableModels = ref([])

const handleThemeToggle = () => {
  themeMode.value = toggleTheme()
}

const selectFeature = (feature) => {
  if (!feature?.route || feature.route === route.path) return
  router.push(feature.route)
}

const goAnalysis = () => router.push('/analysis')

  const saveDatabaseConfig = () => {
    const cfg = cloneDbConfig(dbConfig.value)
  if (!cfg.jdbcUrl.trim()) {
    if (!cfg.server.trim()) {
      dbMessageType.value = 'error'
      dbMessage.value = '服务器不能为空'
      return
    }
    if (!cfg.database.trim()) {
      dbMessageType.value = 'error'
      dbMessage.value = '数据库名不能为空'
      return
    }
  }

  dbConfig.value = cfg
  localStorage.setItem(dbStorageKey, JSON.stringify(toStoredDbConfig(cfg)))
  dbMessageType.value = 'success'
  dbMessage.value = '数据库配置已保存，分析页会自动使用最新配置。'
}

const resetDatabaseConfig = () => {
  dbConfig.value = createDefaultDbConfig()
  localStorage.setItem(dbStorageKey, JSON.stringify(toStoredDbConfig(dbConfig.value)))
  dbMessageType.value = 'success'
  dbMessage.value = '已重置数据库配置。'
}

const saveModelConfig = () => {
  saveModelConfigToLocal(localStorage, modelConfig.value)
  saveModelConfigToSession(sessionStorage, modelConfig.value)
  modelMessageType.value = 'success'
  modelMessage.value = '模型配置已保存到本机浏览器，下次打开会自动带出。'
}

const loadModels = async () => {
  const baseUrl = modelConfig.value.baseUrl?.trim()
  const apiKey = modelConfig.value.apiKey
  if (!apiKey) {
    modelMessageType.value = 'error'
    modelMessage.value = '请先填写 API Key，再获取模型列表。'
    return
  }

  modelLoading.value = true
  modelMessage.value = ''
  try {
    const res = await fetch(buildApiUrl('/api/ai/models'), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${getToken()}`
      },
      body: JSON.stringify({ baseUrl, apiKey })
    })
    const data = await parseJsonSafe(res)
    if (!res.ok) {
      throw new Error(data?.message || '获取模型失败，请检查 Base URL 和 API Key')
    }

    availableModels.value = Array.isArray(data?.models) ? data.models : []
    if (!availableModels.value.length) {
      modelMessageType.value = 'error'
      modelMessage.value = '未获取到可用模型，请确认该服务支持 /models 接口。'
      return
    }
    if (!availableModels.value.includes(modelConfig.value.model)) {
      modelConfig.value.model = availableModels.value[0]
    }
    modelMessageType.value = 'success'
    modelMessage.value = `已获取 ${availableModels.value.length} 个模型，可从下拉列表中选择。`
  } catch (err) {
    modelMessageType.value = 'error'
    modelMessage.value = err?.message || '获取模型失败，请检查 Base URL 和 API Key'
  } finally {
    modelLoading.value = false
  }
}

const resetModelConfig = () => {
  clearModelConfigFromLocal(localStorage)
  clearModelConfigFromSession(sessionStorage)
  modelConfig.value = createDefaultModelConfig()
  availableModels.value = []
  modelMessageType.value = 'success'
  modelMessage.value = '已清空本机保存的模型配置，后端将继续使用环境变量。'
}

</script>

<style scoped>
.settings-main {
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
.settings-actions,
.toggle-inline {
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

.settings-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
  max-width: 1080px;
}

.settings-card {
  display: grid;
  gap: 16px;
  padding: 26px;
  align-content: start;
}

.settings-form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.settings-form-grid label {
  display: grid;
  gap: 8px;
}

.settings-form-grid span,
.toggle-inline span {
  font-size: 13px;
  color: var(--text-secondary);
  font-weight: 700;
}

.setting-section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 2px;
  color: var(--text-primary);
}

.setting-section-title span {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-500), var(--teal-500));
  font-family: var(--font-label);
  font-size: 11px;
  font-weight: 700;
}

.setting-section-title strong {
  font-size: 14px;
}

.setting-toggle-card {
  display: grid;
  grid-template-columns: minmax(220px, 0.4fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
  padding: 16px;
  border: 1px solid var(--border-subtle);
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.1), transparent 24%), var(--bg-surface-3);
}

.setting-toggle-card.slim {
  grid-template-columns: minmax(260px, 0.42fr) minmax(0, 1fr);
}

.setting-explain {
  display: grid;
  gap: 6px;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.setting-explain.compact {
  padding: 0;
}

.setting-explain strong,
.setting-explain p {
  margin: 0;
}

.setting-explain strong {
  font-size: 13px;
  color: var(--text-primary);
}

.setting-explain p {
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.setting-action-row {
  justify-content: flex-end;
  padding-top: 4px;
}

.settings-form-grid input,
.settings-form-grid select {
  min-height: 42px;
  padding: 0 12px;
}

.settings-full-row {
  grid-column: 1 / -1;
}

.empty-note {
  font-size: 13px;
  color: var(--text-secondary);
}

@media (max-width: 980px) {
  .settings-form-grid,
  .setting-toggle-card,
  .setting-toggle-card.slim {
    grid-template-columns: 1fr;
  }

  .ops-header {
    flex-direction: column;
  }
}
</style>
