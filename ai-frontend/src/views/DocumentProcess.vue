<template>
  <div class="workspace-shell doc-shell">
    <WorkspaceSidebar
      eyebrow="文档处理"
      title="文档处理"
      description="上传源文件、填写处理要求，并在同一工作区里完成预览与下载。"
      mark="DOC"
      :active-feature="activeFeature"
      @feature-select="selectFeature"
    />
    <main class="workspace-main doc-main">
      <header class="ops-header surface-panel is-hero">
        <div class="ops-header-copy">
          <p class="brand-eyebrow">文档工作台</p>
          <h1>文档处理</h1>
          <p>上传源文件、填写处理要求，并在同一工作区里完成预览与下载。</p>
          <div class="header-inline-meta">
            <span class="header-annotation">任意格式上传</span>
            <span class="header-dot"></span>
            <span class="header-annotation">源文件预览</span>
            <span class="header-dot"></span>
            <span class="header-annotation">处理完成自动下载</span>
          </div>
        </div>

        <div class="ops-header-actions">
          <div class="header-badges">
            <span :class="['header-pill', selectedFile ? 'active' : '']">{{ fileSummary.label }}</span>
            <span :class="['header-pill', resultPreview ? 'accent' : '']">{{ previewSummary.label }}</span>
          </div>
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

      <section class="doc-brief surface-panel">
        <article class="brief-card">
          <span class="brief-label">文件状态</span>
          <strong>{{ fileSummary.label }}</strong>
          <p>{{ fileSummary.detail }}</p>
        </article>
        <article class="brief-card">
          <span class="brief-label">预览状态</span>
          <strong>{{ previewSummary.label }}</strong>
          <p>{{ previewSummary.detail }}</p>
        </article>
        <article class="brief-card">
          <span class="brief-label">处理模式</span>
          <strong>{{ processModeLabel || '等待处理' }}</strong>
          <p>{{ processModeLabel ? '处理结果会保留当前模式说明。' : '提交后会根据文件类型自动选择最合适的处理方式。' }}</p>
        </article>
      </section>

      <section class="doc-grid">
        <article class="surface-panel doc-form-panel">
          <div class="panel-head">
            <div>
              <p class="brand-eyebrow">上传与要求</p>
              <h2>上传文档并填写要求</h2>
              <p class="panel-note">先选择源文件，再通过要求描述明确输出格式、摘要层级或排版目标。</p>
            </div>
            <span class="status-pill is-brand">{{ selectedFile ? '已选择文件' : '等待上传' }}</span>
          </div>

          <label class="upload-zone">
            <input class="sr-only" type="file" accept="*/*" @change="handleFileChange" />
            <strong>{{ selectedFile ? selectedFile.name : '点击选择文档' }}</strong>
            <span>{{ selectedFile ? `文件大小：${formatSize(selectedFile.size)}` : '支持任意格式，文本文件会自动生成源预览' }}</span>
          </label>

          <div v-if="selectedFile" class="file-meta">
            <span>文件：{{ selectedFile.name }}</span>
            <span>大小：{{ formatSize(selectedFile.size) }}</span>
          </div>

          <div class="suggestion-row">
            <button
              v-for="suggestion in requirementSuggestions"
              :key="suggestion"
              type="button"
              class="suggestion-chip"
              @click="requirement = suggestion"
            >
              {{ suggestion }}
            </button>
          </div>

          <label class="form-field">
            <span class="form-label">处理要求</span>
            <textarea
              v-model="requirement"
              class="form-control is-textarea"
              placeholder="例如：帮我规范格式，并导出为 docx。"
            ></textarea>
          </label>

          <div class="preview-toggle-row">
            <label class="toggle-inline">
              <input v-model="showSourcePreview" type="checkbox" />
              <span>显示源文件预览</span>
            </label>
            <label class="toggle-inline">
              <input v-model="showResultPreview" type="checkbox" :disabled="!resultPreview" />
              <span>显示处理结果预览</span>
            </label>
          </div>

          <button
            type="button"
            class="primary-button submit-btn"
            :disabled="loading || !canSubmit"
            @click="processDocument"
          >
            {{ loading ? '处理中...' : '开始处理并下载' }}
          </button>

          <div v-if="loading" class="info-banner">大文件处理可能需要 2-10 分钟，请耐心等待。</div>
          <div v-if="errorMsg" class="error-banner">{{ errorMsg }}</div>
          <div v-if="successMsg" class="success-banner">{{ successMsg }}</div>
          <div v-if="processModeLabel" :class="processMode === 'text-fallback' ? 'warning-banner' : 'info-banner'">
            {{ processModeLabel }}
          </div>
        </article>

        <section class="surface-panel doc-preview-panel">
          <div class="panel-head">
            <div>
              <p class="brand-eyebrow">结果预览</p>
              <h2>源文件 / 处理结果</h2>
              <p class="panel-note">源文件与处理结果会在同一区域切换展示，减少来回跳转和下载试错。</p>
            </div>
            <div class="preview-tabs">
              <button
                type="button"
                :class="['preview-tab', previewTab === 'source' ? 'active' : '']"
                @click="previewTab = 'source'"
              >
                源文件
              </button>
              <button
                type="button"
                :class="['preview-tab', previewTab === 'result' ? 'active' : '']"
                :disabled="!resultPreview"
                @click="previewTab = 'result'"
              >
                处理结果
              </button>
            </div>
          </div>

        <div v-if="previewTab === 'source'" class="preview-stage">
          <div v-if="!selectedFile" class="empty-callout">
            选择文件后会在这里展示源文件预览或预览限制说明。
          </div>
          <div v-else-if="showSourcePreview" class="preview-shell">
            <div class="preview-head">
              <strong>源文件预览</strong>
              <span>最多显示 {{ SOURCE_PREVIEW_LIMIT }} 字</span>
            </div>
            <p v-if="sourcePreviewLoading" class="info-banner">正在生成源文件预览...</p>
            <p v-else-if="sourcePreviewError" class="warning-banner">{{ sourcePreviewError }}</p>
            <pre v-else class="preview-block">{{ sourcePreview || '(暂无预览内容)' }}</pre>
          </div>
          <div v-else class="empty-callout">已关闭源文件预览，可通过左侧开关重新开启。</div>
        </div>

        <div v-else class="preview-stage">
          <div v-if="!resultPreview" class="empty-callout">
            处理完成后会在这里展示结果预览，并自动触发下载。
          </div>
          <div v-else-if="showResultPreview" class="preview-shell">
            <div class="preview-head">
              <strong>处理结果预览</strong>
              <span>最多显示 {{ RESULT_PREVIEW_LIMIT }} 字</span>
            </div>
            <pre class="preview-block">{{ resultPreview }}</pre>
          </div>
          <div v-else class="empty-callout">已关闭结果预览，可通过左侧开关重新开启。</div>
        </div>
        </section>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkspaceSidebar from '../components/WorkspaceSidebar.vue'
import { clearAuthSession, withAuthHeaders } from '../utils/auth'
import { buildApiUrl, parseJsonSafe } from '../utils/api'
import { getCurrentTheme, toggleTheme } from '../utils/theme'
import { getWorkspaceFeatureKeyByPath } from '../utils/workspace'

const SOURCE_PREVIEW_LIMIT = 5000
const RESULT_PREVIEW_LIMIT = 5000
const REQUEST_TIMEOUT_MS = 720000
const TEXT_EXTENSIONS = new Set([
  'txt', 'md', 'csv', 'json', 'xml', 'html', 'htm',
  'yml', 'yaml', 'log', 'sql', 'java', 'js', 'ts', 'vue', 'properties', 'ini'
])

const requirementSuggestions = [
  '帮我规范格式，并导出为 docx。',
  '请提炼要点，保留原有结构层级。',
  '把内容整理成适合汇报的正式文档。'
]

const router = useRouter()
const route = useRoute()
const themeMode = ref(getCurrentTheme())
const activeFeature = computed(() => getWorkspaceFeatureKeyByPath(route.path))

const selectedFile = ref(null)
const requirement = ref('')
const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')
const resultPreview = ref('')
const processMode = ref('')
const sourcePreview = ref('')
const sourcePreviewLoading = ref(false)
const sourcePreviewError = ref('')
const showSourcePreview = ref(true)
const showResultPreview = ref(true)
const previewTab = ref('source')
let downloadUrl = ''

const canSubmit = computed(() => !!selectedFile.value && !!requirement.value.trim())

const handleThemeToggle = () => {
  themeMode.value = toggleTheme()
}

const selectFeature = (feature) => {
  if (!feature?.route || feature.route === route.path) return
  router.push(feature.route)
}

const forceLogout = () => {
  clearAuthSession()
  router.replace('/login')
}

const getExtension = (fileName = '') => {
  const idx = fileName.lastIndexOf('.')
  return idx > -1 ? fileName.slice(idx + 1).toLowerCase() : ''
}

const isTextFile = (file) => {
  if (!file) return false
  const contentType = (file.type || '').toLowerCase()
  if (contentType.startsWith('text/')) return true
  if (contentType.includes('/json') || contentType.endsWith('+json')) return true
  if (contentType.includes('/xml') || contentType.endsWith('+xml')) return true
  return TEXT_EXTENSIONS.has(getExtension(file.name))
}

const truncatePreviewText = (text = '', maxChars = 5000) => {
  const normalized = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  if (normalized.length <= maxChars) return normalized
  return `${normalized.slice(0, maxChars)}\n\n...(预览已截断)`
}

const decodeBase64Utf8 = (encoded = '') => {
  if (!encoded) return ''
  try {
    const binary = atob(encoded)
    const bytes = Uint8Array.from(binary, (ch) => ch.charCodeAt(0))
    return new TextDecoder('utf-8').decode(bytes)
  } catch {
    return ''
  }
}

const parseServerFileName = (contentDisposition = '') => {
  if (!contentDisposition) return ''
  const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1])
    } catch {
    }
  }
  const normalMatch = contentDisposition.match(/filename="?([^";]+)"?/i)
  return normalMatch?.[1] || ''
}

const buildSourcePreview = async (file) => {
  sourcePreview.value = ''
  sourcePreviewError.value = ''
  if (!file) return

  if (!isTextFile(file)) {
    sourcePreviewError.value = '该文件无法在本地直接预览，可直接提交给后端处理。'
    return
  }

  sourcePreviewLoading.value = true
  try {
    let text = await file.text()
    if (text.charCodeAt(0) === 0xfeff) {
      text = text.slice(1)
    }
    sourcePreview.value = truncatePreviewText(text, SOURCE_PREVIEW_LIMIT)
  } catch {
    sourcePreviewError.value = '源文件预览读取失败，请直接上传处理。'
  } finally {
    sourcePreviewLoading.value = false
  }
}

const handleFileChange = async (event) => {
  const file = event.target?.files?.[0] || null
  selectedFile.value = file
  errorMsg.value = ''
  successMsg.value = ''
  processMode.value = ''
  resultPreview.value = ''
  previewTab.value = 'source'
  showResultPreview.value = true
  await buildSourcePreview(file)
}

const downloadBlob = (blob, fileName) => {
  if (downloadUrl) {
    URL.revokeObjectURL(downloadUrl)
  }
  downloadUrl = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = downloadUrl
  anchor.download = fileName
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
}

const processDocument = async () => {
  if (!canSubmit.value) {
    errorMsg.value = '请先选择文档并填写处理要求'
    return
  }

  loading.value = true
  errorMsg.value = ''
  successMsg.value = ''
  processMode.value = ''

  const formData = new FormData()
  formData.append('file', selectedFile.value)
  formData.append('requirement', requirement.value.trim())
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)

  try {
    const response = await fetch(buildApiUrl('/api/tools/document/process'), {
      method: 'POST',
      headers: withAuthHeaders(),
      body: formData,
      signal: controller.signal
    })

    if (response.status === 401) {
      forceLogout()
      return
    }

    if (!response.ok) {
      const data = await parseJsonSafe(response)
      errorMsg.value = data?.message || '文档处理失败'
      return
    }

    const blob = await response.blob()
    const serverName = parseServerFileName(response.headers.get('content-disposition') || '')
    const fallbackName = `${selectedFile.value?.name || 'document'}-processed`
    const fileName = serverName || fallbackName
    processMode.value = (response.headers.get('x-process-mode') || '').toLowerCase()

    const previewHeader = response.headers.get('x-processed-preview-base64') || ''
    const previewText = decodeBase64Utf8(previewHeader)

    if (previewText) {
      resultPreview.value = truncatePreviewText(previewText, RESULT_PREVIEW_LIMIT)
      showResultPreview.value = true
      previewTab.value = 'result'
    } else {
      const contentType = (response.headers.get('content-type') || '').toLowerCase()
      if (contentType.startsWith('text/') || contentType.includes('/json') || contentType.includes('/xml')) {
        const text = await blob.text()
        resultPreview.value = truncatePreviewText(text, RESULT_PREVIEW_LIMIT)
        showResultPreview.value = true
        previewTab.value = 'result'
      } else {
        resultPreview.value = ''
      }
    }

    downloadBlob(blob, fileName)
    if (processMode.value === 'text-fallback') {
      successMsg.value = '处理完成，结果文件已自动下载。当前为文本回退模式，格式和图片可能有差异。'
    } else {
      successMsg.value = '处理完成，结果文件已自动下载。'
    }
  } catch (error) {
    if (error?.name === 'AbortError') {
      errorMsg.value = '处理超时（超过 12 分钟），请减少文档长度或简化要求后重试。'
    } else {
      errorMsg.value = '文档处理接口不可用'
    }
  } finally {
    clearTimeout(timer)
    loading.value = false
  }
}

const processModeLabel = computed(() => {
  if (processMode.value === 'raw-file') return '当前为原文件模式，优先保留格式与图片。'
  if (processMode.value === 'text-fallback') return '当前为文本回退模式，可能丢失原有版式。'
  return ''
})

const fileSummary = computed(() => {
  if (!selectedFile.value) {
    return {
      label: '等待上传',
      detail: '支持任意格式文件，文本文件会自动生成源预览。'
    }
  }

  return {
    label: '文件已就绪',
    detail: `${selectedFile.value.name} · ${formatSize(selectedFile.value.size)}`
  }
})

const previewSummary = computed(() => {
  if (sourcePreviewLoading.value) {
    return {
      label: '预览生成中',
      detail: '正在读取源文件内容，请稍候。'
    }
  }

  if (resultPreview.value) {
    return {
      label: '结果可预览',
      detail: '处理结果已生成，可切换到结果标签查看。'
    }
  }

  if (sourcePreviewError.value) {
    return {
      label: '预览受限',
      detail: sourcePreviewError.value
    }
  }

  return {
    label: '预览待生成',
    detail: '选择文件后，这里会展示源文件或处理结果预览。'
  }
})

const formatSize = (bytes = 0) => {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

onBeforeUnmount(() => {
  if (downloadUrl) {
    URL.revokeObjectURL(downloadUrl)
    downloadUrl = ''
  }
})
</script>

<style scoped>
.doc-main {
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
  letter-spacing: -0.02em;
}

.ops-header-copy p:last-child {
  margin-top: 10px;
  font-size: 14px;
  color: var(--text-secondary);
}

.header-inline-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
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

.ops-header-actions {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  flex-wrap: wrap;
  flex-direction: column;
}

.header-badges {
  display: flex;
  align-items: flex-start;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
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

.doc-brief {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  padding: 20px 22px;
}

.brief-card {
  display: grid;
  gap: 6px;
  padding: 18px 18px;
  border: 1px solid var(--border-subtle);
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.12), transparent 26%), var(--bg-surface-2);
}

.brief-card strong,
.brief-card p {
  margin: 0;
}

.brief-label {
  font-family: var(--font-label);
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--text-tertiary);
}

.brief-card strong {
  font-size: 18px;
  color: var(--text-primary);
}

.brief-card p {
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.65;
}

.doc-grid {
  display: grid;
  grid-template-columns: minmax(360px, 0.9fr) minmax(0, 1.1fr);
  gap: 20px;
}

.doc-form-panel,
.doc-preview-panel {
  display: grid;
  gap: 16px;
  padding: 26px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.panel-head h2 {
  margin: 0;
  font-size: 22px;
}

.panel-note {
  margin: 8px 0 0;
  max-width: 520px;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.65;
}

.upload-zone {
  display: grid;
  gap: 8px;
  padding: 22px;
  border: 1px dashed var(--border-strong);
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.08), transparent 24%), var(--bg-surface-2);
  cursor: pointer;
  transition:
    border-color var(--duration-fast) var(--ease-standard),
    background var(--duration-fast) var(--ease-standard),
    transform var(--duration-fast) var(--ease-standard);
}

.upload-zone:hover {
  border-color: var(--border-ornament);
  background: linear-gradient(180deg, rgba(79, 123, 255, 0.08), transparent 24%), var(--bg-surface-2);
}

.upload-zone strong {
  font-size: 16px;
}

.upload-zone span {
  color: var(--text-secondary);
  line-height: 1.6;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.file-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  font-size: 13px;
  color: var(--text-secondary);
}

.suggestion-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.suggestion-chip {
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-secondary);
  cursor: pointer;
  font-family: var(--font-label);
  font-size: 11px;
  letter-spacing: 0.08em;
  transition:
    border-color var(--duration-fast) var(--ease-standard),
    background var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.suggestion-chip:hover {
  border-color: var(--border-ornament);
  background: rgba(79, 123, 255, 0.08);
  color: var(--text-primary);
}

.preview-toggle-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.toggle-inline {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  padding: 0 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.04);
  font-size: 13px;
  color: var(--text-secondary);
}

.submit-btn {
  width: 100%;
}

.preview-tabs {
  display: flex;
  gap: 8px;
}

.preview-tab {
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-secondary);
  cursor: pointer;
  font-family: var(--font-label);
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.preview-tab.active {
  color: var(--brand-600);
  border-color: rgba(79, 123, 255, 0.2);
  background: rgba(79, 123, 255, 0.1);
}

.preview-stage {
  min-height: 440px;
}

.preview-shell {
  display: grid;
  gap: 12px;
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
  color: var(--text-secondary);
}

.preview-block {
  margin: 0;
  padding: 16px;
  min-height: 360px;
  border: 1px solid var(--border-subtle);
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.06), transparent 24%), var(--bg-surface-2);
  overflow: auto;
  white-space: pre-wrap;
  line-height: 1.7;
  color: var(--text-primary);
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
}

.empty-callout {
  display: grid;
  place-items: center;
  min-height: 360px;
  padding: 24px;
  border: 1px dashed var(--border-subtle);
  border-radius: 20px;
  color: var(--text-secondary);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.06), transparent 24%), var(--bg-surface-2);
  text-align: center;
  line-height: 1.7;
}

@media (max-width: 1024px) {
  .doc-brief,
  .doc-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 820px) {
  .doc-shell {
    grid-template-columns: 1fr;
    gap: 14px;
  }

  .doc-shell :deep(.workspace-sidebar) {
    position: static;
    order: 1;
    height: auto;
  }

  .doc-main {
    order: 2;
  }

  .ops-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .ops-header-actions,
  .header-badges {
    width: 100%;
  }

  .preview-head,
  .panel-head {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (max-width: 640px) {
  .doc-shell {
    gap: 12px;
  }

  .ops-header,
  .doc-form-panel,
  .doc-preview-panel {
    padding: 18px;
    border-radius: 20px;
  }

  .preview-tabs,
  .suggestion-row,
  .preview-toggle-row {
    width: 100%;
  }
}
</style>
