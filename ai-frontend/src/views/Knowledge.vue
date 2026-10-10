<template>
  <div class="workspace-shell knowledge-shell">
    <WorkspaceSidebar
      eyebrow="资料管理"
      title="资料管理"
      description="资料分类、状态、用途与引用关系总览。"
      mark="KB"
      :active-feature="activeFeature"
      @feature-select="selectFeature"
    />

    <main class="workspace-main knowledge-main">
      <header class="ops-header surface-panel is-hero">
        <div class="ops-header-copy">
          <p class="brand-eyebrow">资料知识库</p>
          <h1>资料知识库</h1>
          <p>把制度、流程、商品说明和培训材料整理成当前账号的知识库，智能对话会优先引用这些资料作为回答依据。</p>
          <div class="header-inline-meta">
            <span class="header-annotation">按账号保存</span>
            <span class="header-dot"></span>
            <span class="header-annotation">智能对话优先引用</span>
            <span class="header-dot"></span>
            <span class="header-annotation">支持多格式文档</span>
          </div>
        </div>
        <div class="ops-header-actions">
          <button class="secondary-button" type="button" @click="goChat">进入智能对话验证</button>
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

      <section class="surface-panel overview-panel">
        <div class="overview-head">
          <div>
            <p class="brand-eyebrow">知识库概览</p>
            <h2>知识库总览</h2>
            <p class="panel-note">展示资料数量、状态、用途覆盖和当前引用方式，帮助老师快速理解知识来源链路。</p>
          </div>
          <span :class="['status-pill', knowledgeDocuments.length ? 'is-brand' : '']">
            {{ knowledgeDocuments.length ? '知识库已启用' : '等待资料入库' }}
          </span>
        </div>

        <div class="workspace-kpi-grid hub-kpi-grid">
          <article class="surface-card workspace-kpi-card">
            <p class="kpi-label">资料总数</p>
            <p class="kpi-value">{{ knowledgeDocuments.length }}</p>
            <p class="kpi-meta">当前账号可供智能对话引用的资料数量</p>
          </article>
          <article class="surface-card workspace-kpi-card">
            <p class="kpi-label">覆盖分类</p>
            <p class="kpi-value">{{ categoryGroups.length }}</p>
            <p class="kpi-meta">当前已覆盖的资料分类数量</p>
          </article>
          <article class="surface-card workspace-kpi-card">
            <p class="kpi-label">总字数</p>
            <p class="kpi-value">{{ totalTextLength }}</p>
            <p class="kpi-meta">用于估算知识库体量和回答依据覆盖面</p>
          </article>
          <article class="surface-card workspace-kpi-card">
            <p class="kpi-label">最近更新</p>
            <p class="kpi-value text-value">{{ latestUpdatedLabel }}</p>
            <p class="kpi-meta">智能对话优先参考当前账号下最近更新的资料</p>
          </article>
        </div>
      </section>

      <section class="knowledge-grid">
        <article class="surface-panel upload-card">
          <div class="panel-head">
            <div>
              <p class="brand-eyebrow">资料入库</p>
              <h2>上传资料</h2>
              <p class="panel-note">支持 txt、md、doc、docx、pdf、xls、xlsx 等文件；上传后会自动纳入当前账号知识库。</p>
            </div>
          </div>

          <div class="warning-banner">
            引用关系：智能对话优先引用当前账号下最近更新的资料。删除资料后，该文件将不再作为回答依据。
          </div>

          <div class="scenario-card">
            <strong>推荐上传内容</strong>
            <div class="scenario-tags">
              <span class="scenario-tag">流程制度</span>
              <span class="scenario-tag">商品与库存资料</span>
              <span class="scenario-tag">培训与说明资料</span>
              <span class="scenario-tag">其他业务资料</span>
            </div>
          </div>

          <div class="knowledge-upload">
            <input ref="knowledgeFileInput" type="file" @change="handleKnowledgeFileChange" />
            <button class="primary-button" type="button" :disabled="knowledgeLoading || !knowledgeFile" @click="uploadKnowledgeDocument">
              {{ knowledgeLoading ? '上传中...' : '上传资料' }}
            </button>
          </div>

          <p v-if="knowledgeMessage" :class="knowledgeMessageType === 'error' ? 'error-banner' : 'success-banner'">{{ knowledgeMessage }}</p>
        </article>

        <article class="surface-panel library-card">
          <div class="panel-head">
            <div>
              <p class="brand-eyebrow">分类视图</p>
              <h2>资料分类</h2>
              <p class="panel-note">仅做前端展示分组，不修改后端字段；帮助快速看清知识库用途分布。</p>
            </div>
          </div>

          <div v-if="categoryGroups.length" class="group-list">
            <section v-for="group in categoryGroups" :key="group.key" class="group-card">
              <div class="group-head">
                <div>
                  <strong>{{ group.label }}</strong>
                  <p>{{ group.description }}</p>
                </div>
                <span class="status-pill is-brand">{{ group.items.length }} 份</span>
              </div>

              <div class="knowledge-list">
                <article v-for="doc in group.items" :key="doc.id" class="knowledge-item">
                  <div class="knowledge-item-copy">
                    <strong>{{ doc.fileName }}</strong>
                    <div class="knowledge-meta-row">
                      <span>{{ doc.fileTypeLabel }}</span>
                      <span>{{ formatTextLength(doc.textLength) }}</span>
                      <span>{{ formatDateTime(doc.updatedAt || doc.createdAt) }}</span>
                    </div>
                    <div class="knowledge-meta-row tags-row">
                      <span class="info-tag">用途：{{ doc.useLabel }}</span>
                      <span class="info-tag">状态：可引用</span>
                    </div>
                  </div>
                  <button class="secondary-button" type="button" :disabled="knowledgeLoading" @click="deleteKnowledgeDocument(doc.id)">删除</button>
                </article>
              </div>
            </section>
          </div>

          <p v-else class="empty-note">还没有上传资料。上传后，这里会按资料分类、状态、用途和引用关系展示知识库结构。</p>
        </article>
      </section>

      <section class="surface-panel relation-panel">
        <div class="panel-head">
          <div>
            <p class="brand-eyebrow">引用链路</p>
            <h2>引用关系</h2>
            <p class="panel-note">把“资料上传 -> 智能对话引用 -> 形成回答依据”的链路直接展示出来。</p>
          </div>
        </div>

        <div class="relation-grid">
          <article class="relation-card">
            <strong>1. 当前账号上传资料</strong>
            <p>资料按当前登录账号保存，不会与其他账号混用。</p>
          </article>
          <article class="relation-card">
            <strong>2. 智能对话优先引用</strong>
            <p>提问时会优先检索当前账号下最近更新的资料内容，作为回答依据。</p>
          </article>
          <article class="relation-card">
            <strong>3. 无依据时明确说明</strong>
            <p>如果知识库中没有相关依据，智能对话会明确说明，而不是编造答案。</p>
          </article>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkspaceSidebar from '../components/WorkspaceSidebar.vue'
import { getToken } from '../utils/auth'
import { buildApiUrl, parseJsonSafe } from '../utils/api'
import { getCurrentTheme, toggleTheme } from '../utils/theme'
import { getWorkspaceFeatureKeyByPath } from '../utils/workspace'

const router = useRouter()
const route = useRoute()
const themeMode = ref(getCurrentTheme())
const activeFeature = computed(() => getWorkspaceFeatureKeyByPath(route.path))

const knowledgeDocuments = ref([])
const knowledgeFile = ref(null)
const knowledgeFileInput = ref(null)
const knowledgeLoading = ref(false)
const knowledgeMessage = ref('')
const knowledgeMessageType = ref('success')

const CATEGORY_DEFINITIONS = [
  {
    key: 'process',
    label: '流程制度',
    description: '适合放置入库、出库、异常处理和制度规范。'
  },
  {
    key: 'inventory',
    label: '商品与库存资料',
    description: '适合放置商品说明、库存规则、SKU 资料和补货说明。'
  },
  {
    key: 'training',
    label: '培训与说明资料',
    description: '适合放置培训文档、操作说明和岗位指引。'
  },
  {
    key: 'other',
    label: '其他资料',
    description: '未命中明显关键词的业务资料。'
  }
]

const classifyDocument = (fileName = '') => {
  const name = String(fileName).toLowerCase()
  if (/(流程|制度|规范|作业|入库|出库|异常)/.test(fileName) || /(process|policy|rule|workflow)/.test(name)) {
    return 'process'
  }
  if (/(商品|库存|sku|货品|物料|补货|供应商)/.test(fileName) || /(inventory|product|stock|supplier)/.test(name)) {
    return 'inventory'
  }
  if (/(培训|说明|手册|指引|教程)/.test(fileName) || /(training|guide|manual|handbook)/.test(name)) {
    return 'training'
  }
  return 'other'
}

const inferUseLabel = (categoryKey) => {
  if (categoryKey === 'process') return '流程与制度解释'
  if (categoryKey === 'inventory') return '商品与库存规则参考'
  if (categoryKey === 'training') return '培训与岗位说明'
  return '补充业务资料'
}

const inferFileTypeLabel = (fileName = '') => {
  const parts = String(fileName).split('.')
  const ext = parts.length > 1 ? parts.pop().toLowerCase() : 'unknown'
  return ext === 'unknown' ? '未知格式' : ext.toUpperCase()
}

const normalizedDocuments = computed(() => {
  return knowledgeDocuments.value.map((doc) => {
    const categoryKey = classifyDocument(doc.fileName)
    return {
      ...doc,
      categoryKey,
      useLabel: inferUseLabel(categoryKey),
      fileTypeLabel: inferFileTypeLabel(doc.fileName)
    }
  })
})

const categoryGroups = computed(() => {
  return CATEGORY_DEFINITIONS
    .map((definition) => ({
      ...definition,
      items: normalizedDocuments.value.filter((doc) => doc.categoryKey === definition.key)
    }))
    .filter((group) => group.items.length > 0)
})

const totalTextLength = computed(() => Number(normalizedDocuments.value.reduce((sum, doc) => sum + Number(doc.textLength || 0), 0)).toLocaleString())

const latestUpdatedLabel = computed(() => {
  const timestamps = normalizedDocuments.value
    .map((doc) => new Date(doc.updatedAt || doc.createdAt).getTime())
    .filter((value) => Number.isFinite(value) && value > 0)

  if (!timestamps.length) return '暂无资料'
  return formatDateTime(new Date(Math.max(...timestamps)).toISOString())
})

const handleThemeToggle = () => {
  themeMode.value = toggleTheme()
}

const selectFeature = (feature) => {
  if (!feature?.route || feature.route === route.path) return
  router.push(feature.route)
}

const goChat = () => router.push('/chat')

const loadKnowledgeDocuments = async () => {
  knowledgeLoading.value = true
  try {
    const res = await fetch(buildApiUrl('/api/settings/knowledge-documents'), {
      headers: { Authorization: `Bearer ${getToken()}` }
    })
    const data = await parseJsonSafe(res)
    if (!res.ok) {
      throw new Error(data?.message || '获取资料列表失败')
    }
    knowledgeDocuments.value = Array.isArray(data) ? data : []
  } catch (err) {
    knowledgeMessageType.value = 'error'
    knowledgeMessage.value = err?.message || '获取资料列表失败'
  } finally {
    knowledgeLoading.value = false
  }
}

const handleKnowledgeFileChange = (event) => {
  knowledgeFile.value = event.target.files?.[0] || null
}

const uploadKnowledgeDocument = async () => {
  if (!knowledgeFile.value) return

  const formData = new FormData()
  formData.append('file', knowledgeFile.value)
  knowledgeLoading.value = true
  knowledgeMessage.value = ''
  try {
    const res = await fetch(buildApiUrl('/api/settings/knowledge-documents'), {
      method: 'POST',
      headers: { Authorization: `Bearer ${getToken()}` },
      body: formData
    })
    const data = await parseJsonSafe(res)
    if (!res.ok) {
      throw new Error(data?.message || '上传资料失败')
    }
    knowledgeMessageType.value = 'success'
    knowledgeMessage.value = '资料已上传，智能对话会自动使用。'
    knowledgeFile.value = null
    if (knowledgeFileInput.value) knowledgeFileInput.value.value = ''
    await loadKnowledgeDocuments()
  } catch (err) {
    knowledgeMessageType.value = 'error'
    knowledgeMessage.value = err?.message || '上传资料失败'
  } finally {
    knowledgeLoading.value = false
  }
}

const deleteKnowledgeDocument = async (id) => {
  knowledgeLoading.value = true
  knowledgeMessage.value = ''
  try {
    const res = await fetch(buildApiUrl(`/api/settings/knowledge-documents/${id}`), {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${getToken()}` }
    })
    const data = await parseJsonSafe(res)
    if (!res.ok) {
      throw new Error(data?.message || '删除资料失败')
    }
    knowledgeMessageType.value = 'success'
    knowledgeMessage.value = '资料已删除。'
    await loadKnowledgeDocuments()
  } catch (err) {
    knowledgeMessageType.value = 'error'
    knowledgeMessage.value = err?.message || '删除资料失败'
  } finally {
    knowledgeLoading.value = false
  }
}

const formatTextLength = (length) => `${Number(length || 0).toLocaleString()} 字`

const formatDateTime = (value) => {
  if (!value) return '未知时间'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

onMounted(loadKnowledgeDocuments)
</script>

<style scoped>
.knowledge-main {
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
  line-height: 1.08;
}

.ops-header-copy p:last-child {
  margin-top: 10px;
  font-size: 14px;
  color: var(--text-secondary);
}

.ops-header-actions,
.header-inline-meta,
.knowledge-upload,
.scenario-tags,
.knowledge-meta-row,
.relation-grid {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.header-inline-meta {
  margin-top: 12px;
}

.header-annotation,
.legend-title {
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

.overview-panel,
.upload-card,
.library-card,
.relation-panel {
  display: grid;
  gap: 18px;
  padding: 26px;
}

.overview-head,
.panel-head,
.group-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.overview-head h2,
.panel-head h2,
.group-head strong {
  margin: 0;
}

.panel-note,
.group-head p,
.relation-card p,
.knowledge-item span,
.empty-note {
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.65;
}

.knowledge-grid {
  display: grid;
  grid-template-columns: minmax(320px, 0.78fr) minmax(0, 1.22fr);
  gap: 20px;
}

.scenario-card,
.group-card,
.relation-card,
.knowledge-item {
  border: 1px solid var(--border-subtle);
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.1), transparent 24%), var(--bg-surface-2);
}

.scenario-card,
.relation-card {
  padding: 16px 18px;
}

.scenario-card strong,
.relation-card strong {
  display: block;
  margin-bottom: 10px;
}

.scenario-tag,
.info-tag {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  padding: 0 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  font-size: 12px;
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.04);
}

.library-card {
  align-content: start;
}

.group-list,
.knowledge-list {
  display: grid;
  gap: 12px;
}

.group-card {
  padding: 20px;
}

.knowledge-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  padding: 16px;
}

.knowledge-item-copy {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.knowledge-item strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tags-row {
  gap: 8px;
}

.relation-grid {
  align-items: stretch;
}

.relation-card {
  flex: 1 1 220px;
}

.text-value {
  font-size: 20px;
}

@media (max-width: 1080px) {
  .knowledge-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 980px) {
  .ops-header,
  .overview-head,
  .panel-head,
  .group-head {
    flex-direction: column;
  }
}

@media (max-width: 640px) {
  .overview-panel,
  .upload-card,
  .library-card,
  .relation-panel,
  .ops-header {
    padding: 18px;
    border-radius: 20px;
  }

  .knowledge-item {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
