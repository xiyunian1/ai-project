<template>
  <div class="chat-layout">
    <WorkspaceSidebar
      eyebrow="智能工作台"
      title="智能工作台"
      description="统一管理对话、分析和业务入口。"
      mark="AI"
      :show-create="true"
      create-label="新建对话"
      :active-feature="activeFeature"
      session-title="最近会话"
      :sessions="sortedSessions"
      :current-session-id="currentSessionId"
      session-badge="AI"
      @create="createNewChat"
      @feature-select="selectFeature"
      @session-select="switchSession"
      @session-delete="deleteSession"
    />

    <main class="main-panel">
      <header class="chat-header">
        <div class="title-wrap">
          <div class="title-copy">
            <p class="brand-eyebrow">智能对话</p>
            <div class="title-mainline">
              <h2 v-if="!editingTitle">{{ allSessions[currentSessionId]?.name || 'WMS 智能助手' }}</h2>
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
            <p class="title-subline">面向仓储运营的对话式工作区，支持资料增强问答和业务说明整理。</p>
            <div class="header-inline-meta">
              <span class="header-annotation">会话自动保存</span>
              <span class="header-dot"></span>
              <span class="header-annotation">已接入资料管理知识文档</span>
              <span class="header-dot"></span>
              <span class="header-annotation">适合日常运营跟进</span>
            </div>
          </div>
        </div>
        <div class="header-actions">
          <div class="header-badges">
            <span class="header-pill active">对话工作区</span>
            <span class="header-pill">资料问答模式</span>
          </div>
          <button
            class="header-btn icon-only"
            type="button"
            :title="themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
            @click="handleThemeToggle"
          >
            <span class="theme-toggle" aria-hidden="true"></span>
          </button>
        </div>
      </header>

      <section ref="scrollRef" class="chat-window">
        <div v-if="chatList.length === 0" class="welcome-box">
          <div class="welcome-logo">AI</div>
          <p class="brand-eyebrow">对话面板</p>
          <h1>你好，我是 WMS 助手</h1>
          <p>你可以在这里推进仓储资料问答、制度解释、异常处理建议和日常运营记录整理。</p>
          <div class="welcome-ledger">
            <span class="ledger-chip">会话自动保存</span>
            <span class="ledger-chip">资料增强问答</span>
            <span class="ledger-chip">优先根据上传资料回答</span>
            <span class="ledger-chip">适合资料和业务问答</span>
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

        <article
          v-for="(msg, index) in chatList"
          :key="index"
          :class="['message-row', msg.role]"
        >
          <div class="avatar">{{ msg.role === 'user' ? '你' : 'AI' }}</div>
          <div class="bubble-wrap">
            <div v-if="msg.image" class="msg-image">
              <img :src="msg.image" alt="图片消息" />
            </div>
            <div
              v-if="msg.role === 'assistant' && !msg.content && loading"
              class="bubble loading-dots"
            >
              <span></span><span></span><span></span>
            </div>
            <template v-else-if="msg.role === 'assistant'">
              <div class="bubble markdown-body" v-html="renderMd(getAssistantAnswer(msg.content))"></div>
              <details v-if="hasAssistantSql(msg.content)" class="sql-details">
                <summary>查看 SQL 明细</summary>
                <pre>{{ getAssistantSql(msg.content) }}</pre>
              </details>
            </template>
            <div v-else class="bubble markdown-body" v-html="renderMd(msg.content)"></div>
          </div>
        </article>
      </section>

      <footer class="input-panel">
        <div class="composer-meta">
          <div class="composer-status">
            <span class="composer-state">{{ loading ? '响应生成中' : '准备就绪' }}</span>
            <p class="composer-hint">
              当前为智能对话模式，适合文本问答、资料问答和业务说明整理；数据查询请使用“分析助手”。
            </p>
          </div>
        </div>

        <div class="input-row">
          <textarea
            v-model="inputVal"
            placeholder="输入问题，按 Enter 发送（Shift + Enter 换行）"
            @keydown.enter.exact.prevent="sendMsg"
          ></textarea>

          <input
            ref="fileInput"
            type="file"
            accept="image/*"
            style="display: none"
            @change="handleImageUpload"
          />

          <div class="composer-actions">
            <button class="icon-btn" type="button" title="添加图片附件（当前文本模型不做图片理解）" @click="triggerFileSelect">🖼</button>
            <button
              class="send-btn"
              type="button"
              :disabled="loading || (!inputVal.trim() && !imageUrl)"
              @click="sendMsg"
            >
              {{ loading ? '发送中...' : '发送' }}
            </button>
          </div>
        </div>

        <div v-if="imageUrl" class="image-preview">
          <img :src="imageUrl" alt="预览图片" />
          <button type="button" class="remove-image" @click="removeImage">移除图片</button>
        </div>
      </footer>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import 'github-markdown-css/github-markdown.css'
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
import { buildModelConfigPayload, loadModelConfigFromLocal } from '../utils/modelConfig'
import { getCurrentTheme, toggleTheme } from '../utils/theme'
import { getWorkspaceFeatureKeyByPath } from '../utils/workspace'

const router = useRouter()
const route = useRoute()
const md = new MarkdownIt({ breaks: true, html: false, linkify: true, typographer: true })
const CHAT_STORAGE_PREFIX = 'WMS_CHATS'
const DEFAULT_SESSION_NAME = '新对话'
const STREAM_TEXT_EVENTS = new Set(['text_generated', 'message', 'answer'])
const STREAM_END_EVENTS = new Set(['workflow_completed', 'stream_end', 'message_end'])
const SQL_TAIL_MARKER = '\n\n---\n'

const inputVal = ref('')
const loading = ref(false)
const scrollRef = ref(null)
const imageUrl = ref('')
const fileInput = ref(null)
const themeMode = ref(getCurrentTheme())
const editingTitle = ref(false)
const editTitleValue = ref('')
const titleInput = ref(null)
const streamAbortController = ref(null)
const streamRequestSeq = ref(0)

const quickPrompts = [
  {
    title: '请根据资料说明低库存预警后的处理流程',
    desc: '适合快速进入制度和处理流程问答。'
  },
  {
    title: '根据已上传资料，整理一份库存异常排查清单',
    desc: '适合生成面向值班或交接的排查要点。'
  },
  {
    title: '帮我解释入库流程里最容易出错的环节',
    desc: '适合做流程解释和业务培训说明。'
  }
]

const resolveCurrentAccountKey = () => resolveAccountKey({
  account: localStorage.getItem(ACCOUNT_KEY),
  token: getToken(),
  name: localStorage.getItem(NAME_KEY)
})

const currentAccount = ref(
  resolveCurrentAccountKey()
)
const getChatStorageKey = () => `${CHAT_STORAGE_PREFIX}_${currentAccount.value}`

const activeFeature = computed(() => getWorkspaceFeatureKeyByPath(route.path))
const allSessions = ref({})
const currentSessionId = ref('')
const chatList = ref([])
const remoteSyncReady = ref(false)
const REMOTE_PAGE_SIZE = 50
let remoteSaveTimer = null
let remoteSaveInFlight = false
let pendingSaveSessionId = ''

const applyPrompt = (prompt) => {
  inputVal.value = prompt
}

const handleThemeToggle = () => {
  themeMode.value = toggleTheme()
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

const sortedSessions = computed(() => {
  return Object.entries(allSessions.value)
    .map(([id, session]) => ({
      id,
      ...session,
      updatedAt: session.updatedAt || session.createdAt || 0
    }))
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
})

const ensureSession = (id) => {
  if (!allSessions.value[id]) {
    allSessions.value[id] = {
      name: DEFAULT_SESSION_NAME,
      messages: [],
      createdAt: Date.now(),
      updatedAt: Date.now(),
      __loadedRemote: true
    }
  }
  const session = allSessions.value[id]
  if (!Array.isArray(session.messages)) session.messages = []
  if (!session.name) session.name = DEFAULT_SESSION_NAME
  if (!session.createdAt) session.createdAt = Date.now()
  if (!session.updatedAt) session.updatedAt = session.createdAt
  if (typeof session.__loadedRemote !== 'boolean') session.__loadedRemote = true
  return session
}

const loadSessionSummariesFromRemote = async () => {
  try {
    let page = 0
    let totalPages = 1
    const merged = {}

    while (page < totalPages) {
      const res = await fetch(buildApiUrl('/api/chat/sessions', { page, size: REMOTE_PAGE_SIZE }), {
        method: 'GET',
        headers: withAuthHeaders()
      })

      if (res.status === 401) {
        forceLogout()
        return null
      }
      if (!res.ok) return null

      const data = await parseJsonSafe(res)
      const items = Array.isArray(data?.items) ? data.items : []
      totalPages = Number.isFinite(Number(data?.totalPages)) ? Number(data.totalPages) : (items.length > 0 ? page + 1 : 0)

      for (const item of items) {
        const id = (item?.sessionId || '').toString().trim()
        if (!id) continue
        merged[id] = {
          name: (item?.name || DEFAULT_SESSION_NAME).toString(),
          messages: [],
          createdAt: Number(item?.createdAt) || Date.now(),
          updatedAt: Number(item?.updatedAt) || Date.now(),
          __loadedRemote: false
        }
      }
      page += 1
    }

    return merged
  } catch {
    return null
  }
}

const loadSessionDetailFromRemote = async (sessionId) => {
  try {
    const res = await fetch(buildApiUrl(`/api/chat/sessions/${encodeURIComponent(sessionId)}`), {
      method: 'GET',
      headers: withAuthHeaders()
    })

    if (res.status === 401) {
      forceLogout()
      return null
    }
    if (res.status === 404) {
      return null
    }
    if (!res.ok) return null

    const data = await parseJsonSafe(res)
    const session = data?.session
    if (!session || typeof session !== 'object') return null
    return {
      name: (session?.name || DEFAULT_SESSION_NAME).toString(),
      messages: Array.isArray(session?.messages) ? session.messages : [],
      createdAt: Number(session?.createdAt) || Date.now(),
      updatedAt: Number(session?.updatedAt) || Date.now(),
      __loadedRemote: true
    }
  } catch {
    return null
  }
}

const toRemoteSessionPayload = (session) => ({
  name: (session?.name || DEFAULT_SESSION_NAME).toString(),
  messages: Array.isArray(session?.messages) ? session.messages : [],
  createdAt: Number(session?.createdAt) || Date.now(),
  updatedAt: Number(session?.updatedAt) || Date.now()
})

const flushRemoteSave = async (force = false) => {
  if (!remoteSyncReady.value || remoteSaveInFlight) return
  if (!pendingSaveSessionId) return
  if (!isTokenValid()) return

  const sessionId = pendingSaveSessionId
  const session = allSessions.value[sessionId]
  if (!session) return

  remoteSaveInFlight = true
  try {
    pendingSaveSessionId = ''
    const res = await fetch(buildApiUrl(`/api/chat/sessions/${encodeURIComponent(sessionId)}`), {
      method: 'PUT',
      keepalive: force,
      headers: withAuthHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(toRemoteSessionPayload(session))
    })

    if (res.status === 401) {
      forceLogout()
      return
    }
    if (!res.ok && !force) {
      pendingSaveSessionId = sessionId
    }
  } catch {
    if (!force) {
      pendingSaveSessionId = sessionId
    }
  } finally {
    remoteSaveInFlight = false
    if (pendingSaveSessionId) {
      if (remoteSaveTimer) clearTimeout(remoteSaveTimer)
      remoteSaveTimer = setTimeout(() => {
        void flushRemoteSave()
      }, 1200)
    }
  }
}

const scheduleRemoteSave = (sessionId = currentSessionId.value) => {
  if (!remoteSyncReady.value) return
  if (!sessionId || !allSessions.value[sessionId]) return
  pendingSaveSessionId = sessionId
  if (remoteSaveTimer) clearTimeout(remoteSaveTimer)
  remoteSaveTimer = setTimeout(() => {
    void flushRemoteSave()
  }, 600)
}

const deleteSessionFromRemote = async (sessionId) => {
  if (!remoteSyncReady.value || !sessionId) return
  try {
    const res = await fetch(buildApiUrl(`/api/chat/sessions/${encodeURIComponent(sessionId)}`), {
      method: 'DELETE',
      headers: withAuthHeaders()
    })
    if (res.status === 401) {
      forceLogout()
    }
  } catch {
  }
}

const saveToLocal = (sessionId = currentSessionId.value) => {
  localStorage.setItem(getChatStorageKey(), JSON.stringify(allSessions.value))
  scheduleRemoteSave(sessionId)
}

const touchSession = () => {
  const session = allSessions.value[currentSessionId.value]
  if (session) session.updatedAt = Date.now()
}

const closeStream = () => {
  if (streamAbortController.value) {
    streamAbortController.value.abort()
    streamAbortController.value = null
  }
}

const flushPendingSessionBeforeLeave = async () => {
  if (!remoteSyncReady.value || !isTokenValid()) return
  if (!pendingSaveSessionId && currentSessionId.value && allSessions.value[currentSessionId.value]) {
    pendingSaveSessionId = currentSessionId.value
  }
  if (remoteSaveTimer) {
    clearTimeout(remoteSaveTimer)
    remoteSaveTimer = null
  }
  await flushRemoteSave(true)
}

const selectFeature = async (feature) => {
  if (!feature?.key) return
  if (feature.route) {
    if (feature.route !== router.currentRoute.value.path) {
      await flushPendingSessionBeforeLeave()
    }
    router.push(feature.route)
  }
}

const createNewChat = () => {
  const id = `chat_${Date.now()}`
  const session = ensureSession(id)
  session.__loadedRemote = true
  void switchSession(id)
  saveToLocal(id)
}

const switchSession = async (id) => {
  closeStream()
  let session = ensureSession(id)
  currentSessionId.value = id
  editingTitle.value = false

  if (!session.__loadedRemote && remoteSyncReady.value) {
    const remoteSession = await loadSessionDetailFromRemote(id)
    if (remoteSession) {
      allSessions.value[id] = {
        ...session,
        ...remoteSession
      }
      session = allSessions.value[id]
      saveToLocal(id)
    } else {
      session.__loadedRemote = true
    }
  }

  chatList.value = Array.isArray(session.messages) ? session.messages : []
  nextTick(scrollToBottom)
}

const deleteSession = (id) => {
  if (!allSessions.value[id]) return
  void deleteSessionFromRemote(id)
  if (id === currentSessionId.value) closeStream()
  delete allSessions.value[id]
  const nextSession = sortedSessions.value[0]
  if (nextSession) {
    void switchSession(nextSession.id)
  } else {
    createNewChat()
  }
  saveToLocal(currentSessionId.value)
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
  const newTitle = editTitleValue.value.trim()
  if (newTitle && currentSessionId.value) {
    allSessions.value[currentSessionId.value].name = newTitle
    touchSession()
    saveToLocal()
  }
  editingTitle.value = false
}

const triggerFileSelect = () => {
  fileInput.value?.click()
}

const resetFileInput = () => {
  if (fileInput.value) fileInput.value.value = ''
}

const scrollToBottom = () => {
  if (!scrollRef.value) return
  scrollRef.value.scrollTop = scrollRef.value.scrollHeight
}

const renderMd = (content) => {
  return DOMPurify.sanitize(md.render(content || ''))
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

const handleImageUpload = (event) => {
  const file = event.target.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = (e) => {
    imageUrl.value = e.target?.result || ''
    resetFileInput()
  }
  reader.readAsDataURL(file)
}

const removeImage = () => {
  imageUrl.value = ''
  resetFileInput()
}

const resolveText = (payload) => payload?.text || payload?.content || payload?.answer || ''
const shouldUseStreamPayload = (payload) => !payload?.event || STREAM_TEXT_EVENTS.has(payload.event)

const setAiContent = (index, content, append = false) => {
  const target = chatList.value[index]
  if (!target) return
  target.content = append ? `${target.content}${content}` : content
  touchSession()
  saveToLocal()
}

const sendImageMessage = async (tempImageUrl, userText, aiMessageIndex) => {
  try {
    if (!ensureAuthorized()) return

    const blob = await fetch(tempImageUrl).then((res) => res.blob())
    const formData = new FormData()
    formData.append('image', blob, 'image.jpg')
    formData.append('query', userText)

    const res = await fetch(buildApiUrl('/api/ai/chat/image'), {
      method: 'POST',
      headers: withAuthHeaders(),
      body: formData
    })

    if (res.status === 401) {
      forceLogout()
      return
    }

    if (!res.ok) {
      setAiContent(aiMessageIndex, '❌ 图片处理失败')
      return
    }

    const text = await res.text()
    let parsedText = text
    try {
      const jsonData = JSON.parse(text)
      parsedText = resolveText(jsonData) || text
    } catch {
      parsedText = text
    }
    setAiContent(aiMessageIndex, parsedText)
  } catch (error) {
    setAiContent(aiMessageIndex, '❌ 后端连接失败')
  } finally {
    loading.value = false
    saveToLocal()
  }
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

  if (dataLines.length > 0) {
    return dataLines.join('\n')
  }

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
    if (!shouldUseStreamPayload(payload)) return false

    const text = resolveText(payload)
    if (text) setAiContent(aiMessageIndex, text, true)
    return false
  } catch {
    setAiContent(aiMessageIndex, raw, true)
    return false
  }
}

const finishStream = (requestId) => {
  if (streamRequestSeq.value !== requestId) return
  loading.value = false
  saveToLocal()
}

const startStream = async (query, aiMessageIndex) => {
  if (!ensureAuthorized()) {
    loading.value = false
    return
  }

  closeStream()
  const controller = new AbortController()
  streamAbortController.value = controller
  const requestId = ++streamRequestSeq.value

  try {
    const res = await fetch(buildApiUrl('/api/ai/chat/stream'), {
      method: 'POST',
      headers: withAuthHeaders({
        'Content-Type': 'application/json',
        Accept: 'text/event-stream'
      }),
      body: JSON.stringify({
        query,
        modelConfig: buildModelConfigPayload(loadModelConfigFromLocal(localStorage))
      }),
      signal: controller.signal
    })

    if (res.status === 401) {
      forceLogout()
      return
    }

    if (!res.ok || !res.body) {
      const data = await parseJsonSafe(res)
      throw new Error(data?.message || `stream request failed: ${res.status}`)
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
  } catch (error) {
    if (controller.signal.aborted) return

    if (!chatList.value[aiMessageIndex]?.content) {
      setAiContent(aiMessageIndex, `❌ ${error?.message || '流式连接失败'}`)
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

  const text = inputVal.value.trim()
  const hasImage = Boolean(imageUrl.value)
  if ((!text && !hasImage) || loading.value) return

  const session = allSessions.value[currentSessionId.value]
  if (!session) return

  closeStream()

  const userContent = hasImage ? `[图片]${text ? ` ${text}` : ''}` : text
  chatList.value.push({
    role: 'user',
    content: userContent,
    ...(hasImage ? { image: imageUrl.value } : {})
  })

  inputVal.value = ''
  if (session.name === DEFAULT_SESSION_NAME && text) {
    session.name = text.slice(0, 12)
  }
  nextTick(scrollToBottom)

  const aiMessageIndex = chatList.value.length
  chatList.value.push({ role: 'assistant', content: '' })
  touchSession()
  saveToLocal()
  loading.value = true

  if (hasImage) {
    const tempImageUrl = imageUrl.value
    imageUrl.value = ''
    resetFileInput()
    await sendImageMessage(tempImageUrl, text, aiMessageIndex)
    return
  }

  startStream(text, aiMessageIndex)
}

onMounted(async () => {
  if (!ensureAuthorized(true)) return

  const accountFromSession = resolveCurrentAccountKey()
  currentAccount.value = accountFromSession

  const remoteSessions = await loadSessionSummariesFromRemote()
  if (remoteSessions !== null) {
    allSessions.value = remoteSessions
  } else {
    const raw = localStorage.getItem(getChatStorageKey())
    if (raw) {
      try {
        allSessions.value = JSON.parse(raw)
      } catch {
        allSessions.value = {}
      }
    } else {
      allSessions.value = {}
    }
  }

  Object.keys(allSessions.value).forEach(ensureSession)
  remoteSyncReady.value = true

  if (sortedSessions.value.length > 0) {
    await switchSession(sortedSessions.value[0].id)
  } else {
    createNewChat()
  }
})

onBeforeUnmount(() => {
  closeStream()
  if (!pendingSaveSessionId && currentSessionId.value && allSessions.value[currentSessionId.value]) {
    pendingSaveSessionId = currentSessionId.value
  }
  if (remoteSaveTimer) {
    clearTimeout(remoteSaveTimer)
    remoteSaveTimer = null
  }
  void flushRemoteSave(true)
})
</script>

<style scoped>
.chat-layout {
  display: grid;
  grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
  height: calc(100vh - 48px);
  min-height: 0;
  gap: 24px;
  padding: 24px;
}

.sidebar {
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
  font-weight: 600;
  line-height: 1.2;
}

.brand-copy p:last-child {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.new-chat-btn,
.header-btn,
.secondary-btn,
.primary-btn,
.send-btn,
.icon-btn {
  min-height: 40px;
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
.primary-btn,
.send-btn {
  color: var(--text-inverse);
  background: var(--brand-500);
  box-shadow: var(--shadow-sm);
}

.new-chat-btn:hover:not(:disabled),
.primary-btn:hover:not(:disabled),
.send-btn:hover:not(:disabled) {
  background: var(--brand-600);
}

.new-chat-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 16px;
  font-weight: 600;
}

.sidebar-nav-section,
.session-list {
  display: grid;
  gap: 10px;
}

.session-title {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-tertiary);
}

.sidebar-nav-grid {
  display: grid;
  gap: 8px;
}

.sidebar-nav-item,
.session-item {
  width: 100%;
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

.sidebar-nav-item:hover,
.session-item:hover {
  border-color: var(--border-subtle);
  background: var(--bg-surface-3);
  color: var(--text-primary);
}

.sidebar-nav-item.active,
.session-item.active {
  color: var(--text-primary);
  border-color: rgba(37, 99, 235, 0.18);
  background: rgba(37, 99, 235, 0.08);
  box-shadow: inset 2px 0 0 var(--brand-500);
}

.sidebar-token,
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
  letter-spacing: 0.04em;
}

.sidebar-nav-label {
  font-size: 14px;
  font-weight: 600;
}

.session-list {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.session-list-scroll {
  flex: 1;
  min-height: 0;
  display: grid;
  gap: 8px;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-gutter: stable;
}

.session-list-scroll::-webkit-scrollbar,
.chat-window::-webkit-scrollbar {
  width: 8px;
}

.session-list-scroll::-webkit-scrollbar-thumb,
.chat-window::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(100, 116, 139, 0.22);
}

.session-list-scroll::-webkit-scrollbar-thumb:hover,
.chat-window::-webkit-scrollbar-thumb:hover {
  background: rgba(100, 116, 139, 0.34);
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
  color: inherit;
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

.main-panel {
  min-width: 0;
  height: calc(100vh - 48px);
  min-height: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 20px;
  overflow: hidden;
}

.chat-header,
.chat-window,
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

.chat-header::after,
.chat-window::after,
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

.chat-header {
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

.title-copy h2 {
  margin: 0;
  font-size: clamp(26px, 3vw, 34px);
  line-height: 1.14;
}

.title-subline {
  margin: 2px 0 0;
  font-size: 14px;
  color: var(--text-secondary);
  max-width: 720px;
  line-height: 1.7;
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

.title-input {
  width: min(420px, 100%);
  max-width: 100%;
  flex: 0 1 420px;
  min-height: 44px;
  padding: 0 14px;
  margin: 0;
}

.title-edit-btn,
.header-btn,
.secondary-btn {
  padding: 0 14px;
  color: var(--text-primary);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.12), transparent 76%), var(--bg-surface-2);
  border-color: var(--border-subtle);
  box-shadow: var(--shadow-xs);
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

.header-btn.icon-only {
  width: 40px;
  padding: 0;
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

.header-btn.subtle {
  min-height: 38px;
  border-radius: 999px;
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

.chat-window {
  min-height: 0;
  overflow: auto;
  padding: clamp(18px, 3vw, 28px);
  overscroll-behavior: contain;
}

.welcome-box {
  display: grid;
  gap: 14px;
  max-width: 920px;
  margin: 20px auto;
  padding: 34px;
  border: 1px solid var(--border-subtle);
  border-radius: 28px;
  background:
    radial-gradient(circle at top right, rgba(79, 123, 255, 0.08), transparent 28%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.08), transparent 24%),
    var(--bg-surface-2);
  box-shadow: var(--shadow-sm), inset 0 1px 0 rgba(255, 255, 255, 0.24);
}

.welcome-box h1,
.welcome-box p {
  margin: 0;
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
  box-shadow: var(--shadow-sm);
}

.welcome-box h1 {
  font-size: clamp(32px, 4vw, 44px);
  line-height: 1.08;
}

.welcome-box p {
  max-width: 620px;
  color: var(--text-secondary);
  font-size: 15px;
  line-height: 1.7;
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
  padding: 16px 18px;
  border: 1px solid var(--border-subtle);
  border-radius: 20px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.08), transparent 28%),
    var(--bg-surface-2);
  box-shadow: var(--shadow-xs);
}

.message-row.user .bubble {
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

.msg-image {
  margin-bottom: 10px;
}

.msg-image img {
  max-width: min(360px, 100%);
  border-radius: 16px;
  border: 1px solid var(--border-subtle);
  box-shadow: var(--shadow-xs);
}

.input-panel {
  display: grid;
  gap: 16px;
  padding: 20px;
}

.composer-meta {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.composer-status {
  display: grid;
  gap: 8px;
  max-width: 720px;
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
  padding: 14px;
  border: 1px solid var(--border-strong);
  border-radius: 26px;
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

.composer-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.icon-btn {
  width: 44px;
  padding: 0;
  color: var(--text-primary);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.12), transparent 72%), var(--bg-muted);
  border-color: var(--border-subtle);
}

.send-btn {
  min-width: 108px;
  padding: 0 18px;
  font-weight: 600;
}

.image-preview {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 18px;
  background: var(--bg-surface-2);
}

.image-preview img {
  max-height: 112px;
  border-radius: 14px;
  border: 1px solid var(--border-subtle);
}

.remove-image {
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid rgba(209, 67, 91, 0.18);
  border-radius: var(--radius-md);
  color: var(--rose-500);
  background: rgba(209, 67, 91, 0.08);
  cursor: pointer;
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
}

@media (max-width: 1024px) {
  .chat-layout {
    grid-template-columns: var(--sidebar-compact-width) minmax(0, 1fr);
  }

  .sidebar {
    padding: 14px 10px;
  }

  .brand-copy,
  .sidebar-nav-label,
  .session-title,
  .session-info {
    display: none;
  }

  .sidebar-nav-item,
  .session-item {
    justify-content: center;
    padding-inline: 10px;
  }
}

@media (max-width: 820px) {
  .chat-layout {
    grid-template-columns: 1fr;
    gap: 14px;
    height: auto;
    min-height: 100vh;
  }

  .sidebar {
    position: static;
    height: auto;
  }

  .main-panel {
    height: auto;
    overflow: visible;
  }

  .chat-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .header-actions,
  .header-tools,
  .composer-meta,
  .input-row {
    width: 100%;
  }
}

@media (max-width: 640px) {
  .chat-layout {
    gap: 12px;
    padding: 12px;
  }

  .chat-header,
  .chat-window,
  .input-panel,
  .sidebar {
    border-radius: 20px;
  }

  .chat-header,
  .chat-window,
  .input-panel {
    padding: 16px;
  }

  .bubble-wrap {
    max-width: calc(100vw - 104px);
  }

  .composer-actions {
    width: 100%;
    justify-content: space-between;
  }

  .input-row {
    flex-direction: column;
    align-items: stretch;
  }

}
</style>
