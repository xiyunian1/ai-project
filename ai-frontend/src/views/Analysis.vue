<template>
  <div class="workspace-shell analysis-layout">
      <WorkspaceSidebar
        eyebrow="分析助手"
        title="分析助手"
        description="自然语言提问、SQL 查询和业务结论生成。"
      mark="BI"
      :show-create="true"
      create-label="新建分析"
      :active-feature="activeFeature"
      session-title="分析会话"
      :sessions="sortedSessions"
      :current-session-id="currentSessionId"
      session-badge="BI"
      @create="createNewChat"
      @feature-select="selectFeature"
      @session-select="switchSession"
      @session-delete="deleteSession"
    />
    <aside v-if="false" class="analysis-sidebar">
      <div class="sidebar-brand">
        <div class="brand-mark">BI</div>
        <div class="brand-copy">
          <p class="brand-eyebrow">Analysis Workspace</p>
          <h2>分析助手</h2>
          <p>保留上下文和数据库配置的分析工作区。</p>
        </div>
      </div>

      <button class="new-chat-btn" type="button" @click="createNewChat">
        <span>+</span>
        <span>新建分析</span>
      </button>

      <section class="session-list">
        <div class="session-title">分析会话</div>
        <div class="session-list-scroll">
          <button
            v-for="session in sortedSessions"
            :key="session.id"
            type="button"
            :class="['session-item', session.id === currentSessionId ? 'active' : '']"
            @click="switchSession(session.id)"
          >
            <span class="session-icon">BI</span>
            <span class="session-info">
              <span class="session-name">{{ session.name || DEFAULT_SESSION_NAME }}</span>
              <span class="session-meta">{{ (session.messages || []).length }} 条消息</span>
            </span>
            <span class="session-delete" @click.stop="deleteSession(session.id)">×</span>
          </button>
        </div>
      </section>

      <div class="sidebar-footer">
        <button class="switch-btn" type="button" @click="goChat">返回智能对话</button>
        <button
          class="switch-btn icon-only"
          type="button"
          :title="themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
          @click="handleThemeToggle"
        >
          <span class="theme-toggle" aria-hidden="true"></span>
        </button>
      </div>
    </aside>

    <main class="workspace-main analysis-main">
      <header class="analysis-header">
        <div class="title-wrap">
          <div class="title-copy">
            <p class="brand-eyebrow">数据分析</p>
            <div class="title-mainline">
              <h1 v-if="!editingTitle">{{ allSessions[currentSessionId]?.name || '分析助手' }}</h1>
              <input
                v-else
                ref="titleInput"
                v-model="editTitleValue"
                class="title-input"
                @keyup.enter="saveTitle"
                @blur="saveTitle"
              />
              <button class="title-edit-btn" type="button" @click="toggleEditTitle">
                {{ editingTitle ? '完成编辑' : '重命名' }}
              </button>
            </div>
            <p class="title-subline">将自然语言问题转为 SQL 查询，并展示可追溯的分析结论。</p>
            <div class="header-inline-meta">
              <span class="header-annotation">连续追问</span>
              <span class="header-dot"></span>
              <span class="header-annotation">SQL 过程可回看</span>
              <span class="header-dot"></span>
              <span class="header-annotation">真实数据库连接</span>
            </div>
          </div>
        </div>

        <div class="header-actions">
          <div class="header-badges">
            <span class="header-pill active">分析工作区</span>
            <span class="header-pill accent">真实数据库</span>
          </div>
          <div class="header-tools">
            <button
              class="switch-btn icon-only"
              type="button"
              :title="themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
              @click="handleThemeToggle"
            >
              <span class="theme-toggle" aria-hidden="true"></span>
            </button>
          </div>
        </div>
      </header>

      <section class="connection-strip" @click="goSettings">
        <div class="connection-strip-copy">
          <span class="connection-mode accent">当前使用真实数据库连接</span>
          <div class="connection-copy-block">
            <strong>数据库连接</strong>
            <span class="connection-summary">{{ dbConfigSummary }}</span>
          </div>
        </div>
        <button class="connection-action" type="button" @click.stop="goSettings">前往系统配置</button>
      </section>

      <section class="analysis-step-panel surface-panel" aria-label="AI 分析步骤">
        <div class="panel-head">
          <div>
            <p class="brand-eyebrow">分析时间线</p>
            <h2>分析过程</h2>
            <p class="panel-note">从问题理解到 SQL 查询，再到结论生成，展示毕业设计中的 AI 辅助决策链路。</p>
          </div>
          <span :class="['status-pill', loading ? 'is-brand' : '']">{{ loading ? '运行中' : '就绪' }}</span>
        </div>

        <div class="analysis-step-list">
          <article v-for="(step, index) in analysisSteps" :key="step.key" :class="['analysis-step', step.status]">
            <span class="analysis-step-index">{{ index + 1 }}</span>
            <div>
              <strong>{{ step.label }}</strong>
              <p>{{ step.detail || step.desc }}</p>
            </div>
          </article>
        </div>
      </section>

      <section ref="scrollRef" class="analysis-window">
        <div v-if="chatList.length === 0" class="welcome-box">
          <div class="welcome-logo">BI</div>
          <p class="brand-eyebrow">分析面板</p>
          <h2>输入库存或商品问题，查看 AI 分析过程。</h2>
          <p>系统会展示问题理解、SQL 查询和结论生成过程，适合毕业设计演示自然语言数据分析能力。</p>
          <div class="welcome-ledger">
            <span class="ledger-chip">真实数据库分析</span>
            <span class="ledger-chip">结果可追问</span>
            <span class="ledger-chip">SQL 明细保留</span>
          </div>
          <div class="welcome-actions">
            <button
              v-for="prompt in quickPrompts"
              :key="prompt.title"
              type="button"
              class="prompt-card"
              @click="applyPrompt(prompt.title)"
            >
              <strong>{{ prompt.title }}</strong>
              <span>{{ prompt.desc }}</span>
            </button>
          </div>
        </div>

        <div v-else class="message-stream">
          <article
            v-for="(msg, index) in chatList"
            :key="index"
            :class="['message-row', msg.role]"
          >
            <div class="avatar">{{ msg.role === 'user' ? '你' : 'AI' }}</div>
            <div class="bubble-wrap">
              <div
                v-if="msg.role === 'assistant' && !msg.content && loading"
                class="bubble loading-dots"
              >
                <span></span><span></span><span></span>
              </div>
              <p v-if="msg.role === 'assistant' && !msg.content && loading && statusText" class="stream-status">
                {{ statusText }}
              </p>
              <template v-else-if="msg.role === 'assistant'">
                <div class="bubble markdown-body" v-html="renderMd(getAssistantAnswer(msg.content))"></div>
                <details v-if="hasAssistantSql(msg.content)" class="sql-details">
                  <summary>查看 SQL 明细</summary>
                  <pre>{{ getAssistantSql(msg.content) }}</pre>
                </details>
              </template>
              <div v-else class="bubble user-bubble markdown-body" v-html="renderMd(msg.content)"></div>
            </div>
          </article>
        </div>
      </section>

      <footer class="input-panel">
        <div class="composer-meta">
          <span class="composer-state">真实数据库</span>
          <p class="composer-hint">{{ dbConfigSummary }}</p>
        </div>
        <div v-if="statusText" class="info-banner">{{ statusText }}</div>

        <div class="input-row">
          <textarea
            v-model="inputVal"
            placeholder="输入分析问题，按 Enter 发送，Shift + Enter 换行"
            @keydown.enter.exact.prevent="sendMsg"
          ></textarea>
          <button
            class="send-btn"
            type="button"
            :disabled="loading || !inputVal.trim()"
            @click="sendMsg"
          >
            {{ loading ? '分析中...' : '发送' }}
          </button>
        </div>
      </footer>
    </main>

  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import 'github-markdown-css/github-markdown.css'
import {
  ACCOUNT_KEY,
  NAME_KEY,
  clearAuthSession,
  getToken,
  isTokenValid,
  resolveAccountKey,
  withAuthHeaders
} from '../utils/auth'
import { buildApiUrl } from '../utils/api'
import {
  buildDbConfigPayload,
  createDefaultDbConfig,
  loadDbConfigFromStorage
} from '../utils/analysisDbConfig'
import { buildModelConfigPayload, loadModelConfigFromLocal } from '../utils/modelConfig'
import {
  completeAnalysisSteps,
  createInitialAnalysisSteps,
  updateAnalysisSteps
} from '../utils/analysisSteps'
import WorkspaceSidebar from '../components/WorkspaceSidebar.vue'
import { getCurrentTheme, toggleTheme } from '../utils/theme'
import { getWorkspaceFeatureKeyByPath } from '../utils/workspace'

const router = useRouter()
const route = useRoute()
const md = new MarkdownIt({ breaks: true, html: false, linkify: true, typographer: true })
const CHAT_STORAGE_PREFIX = 'WMS_ANALYSIS_CHATS'
const DB_CONFIG_STORAGE_PREFIX = 'WMS_ANALYSIS_DB_CONFIG'
const DEFAULT_SESSION_NAME = '新分析'
const STREAM_TEXT_EVENTS = new Set(['text_generated', 'message', 'answer'])
const STREAM_END_EVENTS = new Set(['workflow_completed', 'stream_end', 'message_end'])
const SQL_TAIL_MARKER = '\n\n---\n'

const inputVal = ref('')
const loading = ref(false)
const statusText = ref('')
const analysisSteps = ref(createInitialAnalysisSteps())
const scrollRef = ref(null)
const themeMode = ref(getCurrentTheme())
const editingTitle = ref(false)
const editTitleValue = ref('')
const titleInput = ref(null)
const streamAbortController = ref(null)
const streamRequestSeq = ref(0)

const quickPrompts = [
  {
    title: '分析当前库存和出入库数据里最值得关注的异常',
    desc: '适合快速启动一次运营分析。'
  },
  {
    title: '根据现有数据库连接，帮我总结可疑的低库存风险',
    desc: '适合联动 SQL Server 配置使用。'
  },
  {
    title: '给我一个适合管理层查看的库存数据分析结论',
    desc: '适合输出摘要和下一步建议。'
  }
]

const dbConfig = ref(createDefaultDbConfig())
const modelConfig = ref(loadModelConfigFromLocal(localStorage))

const currentAccount = ref(
  resolveAccountKey({
    account: localStorage.getItem(ACCOUNT_KEY),
    token: getToken(),
    name: localStorage.getItem(NAME_KEY)
  })
)

const getStorageKey = () => `${CHAT_STORAGE_PREFIX}_${currentAccount.value}`
const getDbConfigStorageKey = () => `${DB_CONFIG_STORAGE_PREFIX}_${currentAccount.value}`

const allSessions = ref({})
const currentSessionId = ref('')
const chatList = ref([])
const activeFeature = computed(() => getWorkspaceFeatureKeyByPath(route.path))

const sortedSessions = computed(() => {
  return Object.entries(allSessions.value)
    .map(([id, session]) => ({
      id,
      ...session,
      updatedAt: session.updatedAt || session.createdAt || 0
    }))
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
})

const dbConfigSummary = computed(() => {
  const cfg = dbConfig.value
  if (cfg.jdbcUrl) return `当前：${cfg.jdbcUrl}`
  const server = cfg.server || 'localhost'
  const port = cfg.port || 1433
  const database = cfg.database || '未填写数据库名'
  return `当前：${server}:${port}/${database}`
})

const loadDbConfigFromLocal = () => {
  dbConfig.value = loadDbConfigFromStorage(localStorage, getDbConfigStorageKey())
}

const applyPrompt = (prompt) => {
  inputVal.value = prompt
}

const handleThemeToggle = () => {
  themeMode.value = toggleTheme()
}

const ensureSession = (id) => {
  if (!allSessions.value[id]) {
    allSessions.value[id] = {
      name: DEFAULT_SESSION_NAME,
      messages: [],
      createdAt: Date.now(),
      updatedAt: Date.now()
    }
  }
  const session = allSessions.value[id]
  if (!Array.isArray(session.messages)) session.messages = []
  if (!session.name) session.name = DEFAULT_SESSION_NAME
  if (!session.createdAt) session.createdAt = Date.now()
  if (!session.updatedAt) session.updatedAt = session.createdAt
  return session
}

const saveToLocal = () => {
  localStorage.setItem(getStorageKey(), JSON.stringify(allSessions.value))
}

const touchSession = () => {
  const session = allSessions.value[currentSessionId.value]
  if (session) session.updatedAt = Date.now()
}

const forceLogout = (message = '登录状态已失效，请重新登录') => {
  closeStream()
  clearAuthSession()
  if (message) window.alert(message)
  router.replace('/login')
}

const ensureAuthorized = (silent = false) => {
  if (isTokenValid()) return true
  forceLogout(silent ? '' : '登录状态已过期，请重新登录')
  return false
}

const createNewChat = () => {
  const id = `analysis_${Date.now()}`
  ensureSession(id)
  switchSession(id)
  saveToLocal()
}

const switchSession = (id) => {
  closeStream()
  ensureSession(id)
  currentSessionId.value = id
  chatList.value = allSessions.value[id].messages
  editingTitle.value = false
  nextTick(scrollToBottom)
}

const deleteSession = (id) => {
  if (!allSessions.value[id]) return
  delete allSessions.value[id]
  const nextSession = sortedSessions.value[0]
  if (nextSession) {
    switchSession(nextSession.id)
  } else {
    createNewChat()
  }
  saveToLocal()
}

const toggleEditTitle = () => {
  if (editingTitle.value) {
    saveTitle()
    return
  }
  editingTitle.value = true
  editTitleValue.value = allSessions.value[currentSessionId.value]?.name || DEFAULT_SESSION_NAME
  nextTick(() => {
    titleInput.value?.focus()
    titleInput.value?.select()
  })
}

const saveTitle = () => {
  const title = editTitleValue.value.trim()
  if (title && currentSessionId.value) {
    allSessions.value[currentSessionId.value].name = title
    touchSession()
    saveToLocal()
  }
  editingTitle.value = false
}

const selectFeature = (feature) => {
  if (!feature?.route || feature.route === route.path) return
  router.push(feature.route)
}

const goChat = () => router.push('/chat')
const goSettings = () => router.push('/settings')

const renderMd = (content) => DOMPurify.sanitize(md.render(content || ''))

const scrollToBottom = () => {
  if (!scrollRef.value) return
  scrollRef.value.scrollTop = scrollRef.value.scrollHeight
}

const closeStream = () => {
  if (streamAbortController.value) {
    streamAbortController.value.abort()
    streamAbortController.value = null
  }
}

const setAiContent = (index, content, append = false) => {
  const target = chatList.value[index]
  if (!target) return
  target.content = append ? `${target.content}${content}` : content
  touchSession()
  saveToLocal()
}

const splitAssistantContent = (content) => {
  const text = (content || '').trimEnd()
  if (!text) return { answer: '', sql: '' }

  let answer = text
  let sql = ''
  const markerIdx = text.lastIndexOf(SQL_TAIL_MARKER)
  if (markerIdx >= 0) {
    const tail = text.slice(markerIdx + SQL_TAIL_MARKER.length).trim()
    if (/(?:^|\n)(?:SQL\s*\d*:|SQL:|sql:)\s/im.test(tail) || /(?:^|\n)Rows:/i.test(tail)) {
      sql = tail
      answer = text.slice(0, markerIdx).trimEnd()
    }
  }

  if (!sql) {
    const searchStart = Math.max(0, text.length - 1600)
    const tailPart = text.slice(searchStart)
    const tailMatch = tailPart.match(/(?:^|\n)(?:SQL\s*\d*:|SQL:|sql:)\s[\s\S]*$/i)
    if (tailMatch) {
      sql = tailMatch[0].trim()
      answer = text.slice(0, searchStart + (tailMatch.index || 0)).trimEnd()
    }
  }

  return {
    answer: answer || (sql ? '已完成查询，展开下方查看 SQL 明细。' : ''),
    sql
  }
}

const getAssistantAnswer = (content) => splitAssistantContent(content).answer
const getAssistantSql = (content) => splitAssistantContent(content).sql
const hasAssistantSql = (content) => Boolean(splitAssistantContent(content).sql)

const compressContextText = (text, maxLen = 1600, headLen = 900) => {
  const value = (text || '').trim()
  if (value.length <= maxLen) return value

  const safeMax = Math.max(120, maxLen)
  let safeHead = Math.min(Math.max(40, headLen), safeMax - 20)
  let tailLen = safeMax - safeHead - 8
  if (tailLen < 20) {
    tailLen = 20
    safeHead = safeMax - tailLen - 8
  }
  return `${value.slice(0, safeHead)}\n...\n${value.slice(-tailLen)}`
}

const buildHistoryPayload = (maxItems = 12) => {
  const start = Math.max(0, chatList.value.length - maxItems)
  return chatList.value.slice(start).map((item) => ({
    role: item.role === 'assistant' ? 'assistant' : 'user',
    content: compressContextText(item.content || '', 1600, 900)
  }))
}

const extractSsePayload = (block) => {
  if (!block) return ''
  const lines = block.split(/\r?\n/)
  const dataLines = []
  for (const line of lines) {
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  if (dataLines.length > 0) return dataLines.join('\n')
  return lines
    .filter((line) => line && !line.startsWith(':') && !line.startsWith('event:') && !line.startsWith('id:'))
    .join('\n')
    .trim()
}

const handleStreamPayload = (raw, aiMessageIndex) => {
  if (!raw) return false
  if (raw === '[DONE]') return true

  try {
    const payload = JSON.parse(raw)
    if (STREAM_END_EVENTS.has(payload.event)) return true
    if (payload.event === 'status') {
      const nextStatus = payload.text || payload.content || ''
      statusText.value = nextStatus
      analysisSteps.value = updateAnalysisSteps(analysisSteps.value, nextStatus)
      return false
    }
    if (!payload.event || STREAM_TEXT_EVENTS.has(payload.event)) {
      const text = payload.text || payload.content || payload.answer || ''
      if (text) setAiContent(aiMessageIndex, text, true)
    }
    return false
  } catch {
    setAiContent(aiMessageIndex, raw, true)
    return false
  }
}

const finishStream = (requestId) => {
  if (streamRequestSeq.value !== requestId) return
  loading.value = false
  statusText.value = ''
  analysisSteps.value = completeAnalysisSteps(analysisSteps.value)
  saveToLocal()
}

const startAnalysisStream = async (query, history, aiMessageIndex) => {
  if (!ensureAuthorized()) {
    loading.value = false
    return
  }

  closeStream()
  const controller = new AbortController()
  streamAbortController.value = controller
  const requestId = ++streamRequestSeq.value

  try {
    modelConfig.value = loadModelConfigFromLocal(localStorage)
    const body = {
      query,
      history,
      dbConfig: buildDbConfigPayload(dbConfig.value),
      modelConfig: buildModelConfigPayload(modelConfig.value)
    }

    const res = await fetch(buildApiUrl('/api/ai/chat/analyze/stream'), {
      method: 'POST',
      headers: withAuthHeaders({
        'Content-Type': 'application/json',
        Accept: 'text/event-stream'
      }),
      body: JSON.stringify(body),
      signal: controller.signal
    })

    if (res.status === 401) {
      forceLogout()
      return
    }

    if (!res.ok || !res.body) {
      throw new Error(`stream request failed: ${res.status}`)
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const blocks = buffer.split(/\r?\n\r?\n/)
      buffer = blocks.pop() || ''

      for (const block of blocks) {
        const payload = extractSsePayload(block)
        const shouldStop = handleStreamPayload(payload, aiMessageIndex)
        nextTick(scrollToBottom)
        if (shouldStop) {
          finishStream(requestId)
          return
        }
      }
    }

    const tail = extractSsePayload(buffer.trim())
    if (tail) {
      handleStreamPayload(tail, aiMessageIndex)
      nextTick(scrollToBottom)
    }
    finishStream(requestId)
  } catch {
    if (controller.signal.aborted) return
    if (!chatList.value[aiMessageIndex]?.content) {
      setAiContent(aiMessageIndex, '分析流式接口不可用')
    }
    if (!isTokenValid()) {
      forceLogout()
      return
    }
    finishStream(requestId)
  } finally {
    if (streamAbortController.value === controller) {
      streamAbortController.value = null
    }
  }
}

const sendMsg = async () => {
  if (!ensureAuthorized()) return

  const query = inputVal.value.trim()
  if (!query || loading.value) return

  const session = allSessions.value[currentSessionId.value]
  if (!session) return

  const history = buildHistoryPayload()
  chatList.value.push({ role: 'user', content: query })
  chatList.value.push({ role: 'assistant', content: '' })
  const aiIndex = chatList.value.length - 1

  inputVal.value = ''
  if (session.name === DEFAULT_SESSION_NAME) {
    session.name = query.slice(0, 16)
  }
  loading.value = true
  statusText.value = ''
  analysisSteps.value = createInitialAnalysisSteps()
  touchSession()
  saveToLocal()
  nextTick(scrollToBottom)

  await startAnalysisStream(query, history, aiIndex)
}

const applyRouteQueryPrompt = () => {
  const routeQuery = route.query.query
  const prompt = Array.isArray(routeQuery) ? routeQuery[0] : routeQuery
  if (!prompt || typeof prompt !== 'string') return
  if (inputVal.value.trim()) return
  inputVal.value = prompt.slice(0, 2000)
}

onMounted(() => {
  if (!ensureAuthorized(true)) return

  loadDbConfigFromLocal()

  const raw = localStorage.getItem(getStorageKey())
  if (raw) {
    try {
      allSessions.value = JSON.parse(raw)
    } catch {
      allSessions.value = {}
    }
  }

  Object.keys(allSessions.value).forEach((id) => ensureSession(id))
  if (sortedSessions.value.length > 0) {
    switchSession(sortedSessions.value[0].id)
  } else {
    createNewChat()
  }

  applyRouteQueryPrompt()
})

onBeforeUnmount(() => {
  closeStream()
})
</script>

<style scoped>
.analysis-layout {
  display: grid;
  grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
  height: calc(100vh - 48px);
  min-height: 0;
  gap: 24px;
  padding: 24px;
}

.analysis-sidebar {
  position: sticky;
  top: 20px;
  height: calc(100vh - 40px);
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px;
  border: 1px solid var(--border-subtle);
  border-radius: 24px;
  overflow: hidden;
  background: var(--bg-surface-1);
  backdrop-filter: blur(18px);
  box-shadow: var(--shadow-md);
}

.sidebar-brand {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.brand-copy h2,
.brand-copy p {
  margin: 0;
}

.brand-copy h2 {
  font-size: 22px;
  line-height: 1.2;
}

.brand-copy p:last-child {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.new-chat-btn,
.switch-btn,
.title-edit-btn,
.secondary-btn,
.send-btn,
.connection-action {
  min-height: 40px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition:
    transform var(--duration-fast) var(--ease-standard),
    background var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.new-chat-btn,
.send-btn {
  color: var(--text-inverse);
  background: var(--brand-500);
  box-shadow: var(--shadow-sm);
}

.new-chat-btn:hover:not(:disabled),
.send-btn:hover:not(:disabled) {
  background: var(--brand-600);
}

.new-chat-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-weight: 600;
}

.session-list {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.session-list-scroll {
  flex: 1;
  min-height: 0;
  display: grid;
  gap: 8px;
  grid-auto-rows: 92px;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-gutter: stable;
}

.session-list-scroll::-webkit-scrollbar,
.analysis-window::-webkit-scrollbar {
  width: 8px;
}

.session-list-scroll::-webkit-scrollbar-thumb,
.analysis-window::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(100, 116, 139, 0.22);
}

.session-list-scroll::-webkit-scrollbar-thumb:hover,
.analysis-window::-webkit-scrollbar-thumb:hover {
  background: rgba(100, 116, 139, 0.34);
}

.session-title {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-tertiary);
}

.session-item {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: 14px;
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.session-item:hover {
  border-color: var(--border-subtle);
  background: var(--bg-surface-3);
  color: var(--text-primary);
}

.session-item.active {
  color: var(--text-primary);
  border-color: rgba(37, 99, 235, 0.18);
  background: rgba(37, 99, 235, 0.08);
  box-shadow: inset 2px 0 0 var(--brand-500);
}

.session-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 42px;
  height: 28px;
  padding: 0 8px;
  border-radius: 999px;
  border: 1px solid var(--border-subtle);
  background: var(--bg-surface-2);
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 700;
}

.session-info {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 2px;
}

.session-name,
.session-meta {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-name {
  font-size: 14px;
  font-weight: 600;
}

.session-meta {
  font-size: 12px;
  color: var(--text-tertiary);
}

.session-delete {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: var(--text-tertiary);
}

.session-delete:hover {
  background: rgba(209, 67, 91, 0.1);
  color: var(--rose-500);
}

.sidebar-footer {
  display: grid;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
}

.switch-btn,
.title-edit-btn,
.secondary-btn,
.connection-action {
  color: var(--text-primary);
  background: var(--bg-surface-2);
  border-color: var(--border-subtle);
}

.switch-btn.icon-only {
  width: 40px;
  padding: 0;
}

.analysis-main {
  min-width: 0;
  height: calc(100vh - 48px);
  min-height: 0;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  gap: 20px;
  overflow: hidden;
}

.analysis-header,
.connection-strip,
.analysis-window,
.input-panel {
  position: relative;
  border: 1px solid var(--border-subtle);
  border-radius: 28px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.08), transparent 22%),
    var(--bg-surface-1);
  backdrop-filter: blur(22px);
  box-shadow: var(--shadow-sm), inset 0 1px 0 rgba(255, 255, 255, 0.28);
  overflow: hidden;
}

.analysis-header::after,
.connection-strip::after,
.analysis-window::after,
.input-panel::after {
  content: '';
  position: absolute;
  left: 24px;
  right: 24px;
  top: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--border-ornament), transparent);
  pointer-events: none;
}

.analysis-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding: 28px 30px;
  background:
    linear-gradient(135deg, rgba(79, 123, 255, 0.12), transparent 52%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.16), transparent 20%),
    var(--bg-surface-1);
}

.title-wrap {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
}

.title-copy {
  min-width: 0;
  display: grid;
  gap: 12px;
}

.title-mainline {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.title-copy h1 {
  margin: 0;
  font-size: clamp(28px, 3vw, 36px);
  line-height: 1.12;
  letter-spacing: -0.02em;
}

.title-input {
  width: min(420px, 100%);
  max-width: 100%;
  flex: 0 1 420px;
  min-height: 44px;
  padding: 0 14px;
  margin: 0;
}

.title-subline {
  margin: 0;
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.7;
  max-width: 720px;
}

.header-inline-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
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

.title-edit-btn {
  min-height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  color: var(--text-secondary);
  background: transparent;
  box-shadow: none;
  font-size: 13px;
  font-weight: 600;
}

.title-edit-btn:hover {
  color: var(--text-primary);
  border-color: rgba(37, 99, 235, 0.18);
  background: rgba(37, 99, 235, 0.08);
  box-shadow: none;
}

.header-actions,
.header-badges {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  flex-wrap: wrap;
}

.header-actions {
  min-width: 0;
  flex-direction: column;
  justify-content: flex-end;
}

.header-tools {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  width: 100%;
}

.header-pill {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-secondary);
  font-family: var(--font-label);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.header-pill.active {
  color: var(--brand-600);
  border-color: rgba(79, 123, 255, 0.2);
  background: rgba(79, 123, 255, 0.1);
}

.header-pill.accent {
  color: var(--teal-500);
  border-color: rgba(30, 168, 153, 0.2);
  background: rgba(30, 168, 153, 0.08);
}

.connection-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  flex-wrap: wrap;
  cursor: pointer;
}

.connection-strip-copy {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  gap: 14px;
  flex-wrap: wrap;
}

.connection-mode {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  padding: 0 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-secondary);
  font-family: var(--font-label);
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.connection-mode.accent {
  color: var(--teal-500);
  border-color: rgba(30, 168, 153, 0.2);
  background: rgba(30, 168, 153, 0.08);
}

.connection-copy-block {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.connection-copy-block strong {
  font-size: 14px;
  color: var(--text-primary);
}

.connection-summary {
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.connection-action {
  min-height: 36px;
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.12), transparent 76%), var(--bg-surface-2);
  border-color: var(--border-subtle);
  box-shadow: var(--shadow-xs);
}

.analysis-step-panel {
  display: grid;
  gap: 18px;
  padding: 20px;
}

.analysis-step-list {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.analysis-step {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-height: 108px;
  padding: 16px;
  border: 1px solid var(--border-subtle);
  border-radius: 18px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.07), transparent 34%),
    var(--bg-surface-2);
  color: var(--text-secondary);
}

.analysis-step::after {
  content: '';
  position: absolute;
  left: 18px;
  right: 18px;
  bottom: 0;
  height: 2px;
  border-radius: 999px;
  background: transparent;
}

.analysis-step.done {
  border-color: rgba(30, 168, 153, 0.22);
  background:
    linear-gradient(180deg, rgba(30, 168, 153, 0.09), transparent 36%),
    var(--bg-surface-2);
}

.analysis-step.active {
  border-color: rgba(79, 123, 255, 0.34);
  color: var(--text-primary);
  background:
    radial-gradient(circle at top right, rgba(79, 123, 255, 0.13), transparent 40%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.08), transparent 32%),
    var(--bg-surface-2);
  box-shadow: var(--shadow-sm);
}

.analysis-step.active::after {
  background: linear-gradient(90deg, var(--brand-500), var(--teal-500));
}

.analysis-step-index {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 10px;
  color: var(--text-secondary);
  background: rgba(148, 163, 184, 0.12);
  font-family: var(--font-label);
  font-size: 12px;
  font-weight: 800;
}

.analysis-step.done .analysis-step-index,
.analysis-step.active .analysis-step-index {
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-500), var(--teal-500));
}

.analysis-step strong,
.analysis-step p {
  margin: 0;
}

.analysis-step strong {
  color: var(--text-primary);
  font-size: 14px;
}

.analysis-step p {
  margin-top: 6px;
  display: -webkit-box;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.55;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.analysis-window {
  min-height: 0;
  overflow: auto;
  padding: clamp(18px, 3vw, 28px);
  overscroll-behavior: contain;
}

.welcome-box {
  display: grid;
  gap: 14px;
  max-width: 920px;
  margin: 18px auto;
  padding: 30px;
  border: 1px solid var(--border-subtle);
  border-radius: 28px;
  background:
    radial-gradient(circle at top right, rgba(79, 123, 255, 0.08), transparent 28%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.08), transparent 24%),
    var(--bg-surface-2);
  box-shadow: var(--shadow-sm);
}

.welcome-logo {
  width: 64px;
  height: 64px;
  display: grid;
  place-items: center;
  border-radius: 18px;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-500), var(--teal-500));
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.welcome-box h2,
.welcome-box p {
  margin: 0;
}

.welcome-box h2 {
  font-size: clamp(32px, 4vw, 44px);
  line-height: 1.08;
}

.welcome-box p {
  max-width: 620px;
  font-size: 15px;
  line-height: 1.7;
  color: var(--text-secondary);
}

.welcome-ledger {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.ledger-chip {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  padding: 0 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-secondary);
  font-family: var(--font-label);
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.welcome-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.prompt-card {
  display: grid;
  gap: 8px;
  padding: 18px;
  border: 1px solid var(--border-subtle);
  border-radius: 20px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.08), transparent 28%),
    var(--bg-surface-1);
  cursor: pointer;
  text-align: left;
  box-shadow: var(--shadow-xs);
}

.prompt-card:hover {
  border-color: var(--border-ornament);
  background:
    linear-gradient(180deg, rgba(79, 123, 255, 0.08), transparent 28%),
    var(--bg-surface-1);
}

.prompt-card strong {
  font-size: 15px;
  color: var(--text-primary);
}

.prompt-card span {
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.message-row {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.message-row.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 12px;
  color: var(--text-inverse);
  background: linear-gradient(135deg, #475569, #64748b);
  font-size: 13px;
  font-weight: 700;
}

.message-row.user .avatar {
  background: linear-gradient(135deg, var(--brand-500), var(--teal-500));
}

.bubble-wrap {
  max-width: min(920px, 82%);
}

.bubble {
  padding: 15px 16px;
  border: 1px solid var(--border-subtle);
  border-radius: 20px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.08), transparent 28%),
    var(--bg-surface-2);
  box-shadow: var(--shadow-xs);
}

.user-bubble {
  border-color: rgba(79, 123, 255, 0.18);
  background: linear-gradient(135deg, rgba(79, 123, 255, 0.12), rgba(30, 168, 153, 0.05));
}

.sql-details {
  margin-top: 10px;
  overflow: hidden;
  border: 1px solid var(--border-subtle);
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.08), transparent 26%), var(--bg-surface-2);
}

.sql-details summary {
  padding: 12px 14px;
  cursor: pointer;
  user-select: none;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}

.sql-details pre {
  margin: 0;
  padding: 14px;
  border-top: 1px solid var(--border-subtle);
  background: var(--bg-muted);
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.stream-status {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.input-panel {
  display: grid;
  gap: 14px;
  padding: 18px;
}

.composer-meta {
  display: grid;
  gap: 8px;
}

.composer-state {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  min-height: 30px;
  padding: 0 12px;
  border: 1px solid rgba(79, 123, 255, 0.2);
  border-radius: 999px;
  background: rgba(79, 123, 255, 0.08);
  color: var(--brand-600);
  font-family: var(--font-label);
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.composer-hint {
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
}

.input-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 14px;
  padding: 12px;
  border: 1px solid var(--border-strong);
  border-radius: 24px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.08), transparent 24%),
    var(--bg-surface-2);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

textarea {
  flex: 1;
  min-height: 74px;
  max-height: 180px;
  padding: 8px 6px;
  border: none;
  background: transparent;
  box-shadow: none;
  color: var(--text-primary);
  font-size: 15px;
  line-height: 1.7;
}

.send-btn {
  min-width: 108px;
}

.loading-dots {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.loading-dots span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-500);
  animation: blink 1.1s infinite ease-in-out;
}

.loading-dots span:nth-child(2) {
  animation-delay: 0.15s;
}

.loading-dots span:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes blink {
  0%,
  80%,
  100% {
    opacity: 0.35;
    transform: scale(0.8);
  }
  40% {
    opacity: 1;
    transform: scale(1);
  }
}

:deep(.markdown-body) {
  color: var(--text-primary);
  background: transparent;
  font-size: 14px;
  line-height: 1.7;
}

@media (max-width: 1200px) {
  .header-actions {
    width: 100%;
  }

  .welcome-actions {
    grid-template-columns: 1fr;
  }

  .analysis-step-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 1024px) {
  .analysis-layout {
    grid-template-columns: var(--sidebar-compact-width) minmax(0, 1fr);
  }

  .analysis-sidebar {
    padding: 14px 10px;
  }

  .brand-copy,
  .session-title,
  .session-info,
  .switch-btn:not(.icon-only) {
    display: none;
  }

  .session-list-scroll {
    grid-auto-rows: 72px;
  }

  .session-item,
  .sidebar-footer button {
    justify-content: center;
    padding-inline: 10px;
  }
}

@media (max-width: 820px) {
  .analysis-layout {
    grid-template-columns: 1fr;
    gap: 14px;
    height: auto;
    min-height: 100vh;
  }

  .analysis-main {
    order: 2;
    height: auto;
    overflow: visible;
  }

  .analysis-layout :deep(.workspace-sidebar) {
    position: static;
    order: 1;
    height: auto;
  }

  .analysis-header,
  .connection-strip,
  .input-row {
    flex-direction: column;
    align-items: flex-start;
  }

  .header-actions,
  .header-badges,
  .header-tools,
  .connection-strip-copy,
  .connection-action {
    width: 100%;
  }

  .analysis-step-list {
    grid-template-columns: 1fr;
  }

}

@media (max-width: 640px) {
  .analysis-layout {
    gap: 12px;
    padding: 12px;
  }

  .analysis-header,
  .connection-strip,
  .analysis-step-panel,
  .analysis-window,
  .input-panel,
  .analysis-sidebar {
    border-radius: 20px;
  }

  .analysis-header,
  .connection-strip,
  .analysis-step-panel,
  .analysis-window,
  .input-panel {
    padding: 16px;
  }

  .bubble-wrap {
    max-width: calc(100vw - 104px);
  }

  .input-row {
    width: 100%;
  }
}
</style>
