<template>
  <div class="workspace-shell inventory-shell">
    <WorkspaceSidebar
      eyebrow="库存洞察"
      title="库存洞察"
      description="库存风险看板、优先处置清单与分析联动。"
      mark="INV"
      :active-feature="activeFeature"
      @feature-select="selectFeature"
    />

    <main class="workspace-main inventory-main">
      <header class="ops-header surface-panel is-hero">
        <div class="ops-header-copy">
          <p class="brand-eyebrow">库存风险看板</p>
          <h1>库存洞察</h1>
          <p>先回答今天该优先处理哪些商品，再补充 SKU 查询与分析联动，形成一页式库存风险看板。</p>
          <div class="header-inline-meta">
            <span class="header-annotation">今日风险总览</span>
            <span class="header-dot"></span>
            <span class="header-annotation">风险分级</span>
            <span class="header-dot"></span>
            <span class="header-annotation">处置联动</span>
          </div>
        </div>

        <div class="ops-header-actions">
          <div class="header-badges">
            <span :class="['header-pill', boardStatus.tone === 'alert' ? 'accent' : 'active']">{{ boardStatus.label }}</span>
            <span class="header-pill">{{ boardNarrative }}</span>
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

      <section class="surface-panel overview-panel">
        <div class="overview-head">
          <div>
            <p class="brand-eyebrow">今日重点</p>
            <h2>今日风险总览</h2>
            <p class="panel-note">先看总体风险，再进入优先处理清单和分析联动。</p>
          </div>
          <button class="secondary-button" type="button" :disabled="lowStockLoading" @click="loadLowStock">
            {{ lowStockLoading ? '刷新中...' : '刷新风险看板' }}
          </button>
        </div>

        <div class="workspace-kpi-grid risk-kpi-grid">
          <article class="surface-card workspace-kpi-card risk-kpi-card">
            <p class="kpi-label">今日风险商品数</p>
            <p class="kpi-value">{{ lowStockRows.length }}</p>
            <p class="kpi-meta">当前低库存且需要优先判断的商品数量</p>
          </article>
          <article class="surface-card workspace-kpi-card risk-kpi-card is-alert">
            <p class="kpi-label">紧急补货</p>
            <p class="kpi-value">{{ urgentCount }}</p>
            <p class="kpi-meta">库存极低或接近断货，建议优先补货</p>
          </article>
          <article class="surface-card workspace-kpi-card risk-kpi-card is-amber">
            <p class="kpi-label">临近有效期</p>
            <p class="kpi-value">{{ expiringCount }}</p>
            <p class="kpi-meta">兼具库存与时效压力，需要核查流转节奏</p>
          </article>
          <article class="surface-card workspace-kpi-card risk-kpi-card">
            <p class="kpi-label">优先处理供应商数</p>
            <p class="kpi-value">{{ prioritySupplierCount }}</p>
            <p class="kpi-meta">用于判断是否需要集中沟通补货与交付</p>
          </article>
        </div>
      </section>

      <section class="inventory-grid">
        <article class="surface-panel priority-panel">
          <div class="panel-head">
            <div>
              <p class="brand-eyebrow">优先队列</p>
              <h2>优先处理清单</h2>
              <p class="panel-note">按风险分级展示最值得关注的商品，支持一键进入分析助手生成处理建议。</p>
            </div>
            <span class="status-pill is-brand">风险分级</span>
          </div>

          <div v-if="lowStockError" class="error-banner">{{ lowStockError }}</div>

          <div v-else-if="rankedLowStockRows.length" class="priority-list">
            <div class="priority-toolbar">
              <div class="legend-card">
                <span class="legend-title">风险图例</span>
                <div class="legend-items">
                  <span class="legend-pill is-red">紧急补货</span>
                  <span class="legend-pill is-amber">临近有效期</span>
                  <span class="legend-pill is-blue">重点观察</span>
                </div>
              </div>
              <div class="filter-card">
                <span class="legend-title">筛选视角</span>
                <div class="filter-actions">
                  <button :class="['filter-chip', riskFilter === 'all' ? 'active' : '']" type="button" @click="riskFilter = 'all'">全部风险</button>
                  <button :class="['filter-chip', riskFilter === 'urgent' ? 'active' : '']" type="button" @click="riskFilter = 'urgent'">仅看紧急补货</button>
                  <button :class="['filter-chip', riskFilter === 'expiry' ? 'active' : '']" type="button" @click="riskFilter = 'expiry'">仅看临近有效期</button>
                </div>
              </div>
            </div>

            <article v-for="row in filteredLowStockRows" :key="row.id" :class="['priority-card', row.riskClass]">
              <div class="priority-card-head">
                <div>
                  <strong>{{ row.productName || '未命名商品' }}</strong>
                  <p>{{ row.riskReason }}</p>
                </div>
                <span :class="['status-pill', row.statusClass]">{{ row.riskLabel }}</span>
              </div>

              <div class="priority-metrics">
                <div>
                  <span>商品编号</span>
                  <strong>{{ row.productId || '-' }}</strong>
                </div>
                <div>
                  <span>当前库存</span>
                  <strong>{{ row.currentStock ?? row.stockQty ?? '-' }}</strong>
                </div>
                <div>
                  <span>供应商</span>
                  <strong>{{ row.supplierName || '待确认' }}</strong>
                </div>
                <div>
                  <span>有效期</span>
                  <strong>{{ row.expiryDate || '未提供' }}</strong>
                </div>
              </div>

              <div class="priority-actions">
                <span class="action-tag">建议动作：{{ row.actionLabel }}</span>
                <button class="secondary-button" type="button" @click="prefillKeyword(row.productName)">查看明细</button>
                <button class="primary-button" type="button" @click="analyzeLowStock(row)">生成处理建议</button>
              </div>
            </article>
          </div>

          <div v-else class="success-banner">当前没有低库存风险商品，适合切换到商品检索做人工复核。</div>
        </article>

        <aside class="inventory-side">
          <article class="surface-panel operator-panel">
            <p class="brand-eyebrow">处置信号</p>
            <h2>处置建议</h2>
            <div class="signal-stack">
              <article class="signal-card">
                <strong>{{ boardStatus.label }}</strong>
                <p>{{ boardStatus.detail }}</p>
              </article>
              <article class="signal-card muted">
                <strong>建议动作</strong>
                <p>{{ nextAction }}</p>
              </article>
            </div>
          </article>

          <article class="surface-panel inventory-query-panel">
            <div class="panel-head">
              <div>
                <p class="brand-eyebrow">商品检索</p>
                <h2>商品辅助检索</h2>
                <p class="panel-note">当你需要补充查看某个商品的 SKU、库存和供应商信息时，再使用这个辅助查询区。</p>
              </div>
              <button class="secondary-button" type="button" @click="queryByName" :disabled="queryLoading">
                {{ queryLoading ? '查询中...' : '查询明细' }}
              </button>
            </div>

            <div class="query-bar">
              <input
                v-model="keyword"
                class="form-control"
                placeholder="输入商品名称，例如：苹果"
                @keyup.enter="queryByName"
              />
            </div>

            <div v-if="queryError" class="error-banner">{{ queryError }}</div>

            <div v-if="queryRows.length" class="table-shell compact-shell">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>商品名称</th>
                    <th>商品编号</th>
                    <th>现有库存</th>
                    <th>供应商</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in queryRows" :key="row.id">
                    <td>{{ row.productName || '-' }}</td>
                    <td>{{ row.productId || '-' }}</td>
                    <td>{{ row.currentStock ?? row.stockQty ?? '-' }}</td>
                    <td>{{ row.supplierName || '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-else-if="querySearched" class="info-banner">没有查询到匹配商品，可以回到优先处理清单继续查看高风险对象。</div>
            <div v-else class="empty-callout">当你想核对某个商品的基础信息时，再在这里补充查询。</div>
          </article>
        </aside>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkspaceSidebar from '../components/WorkspaceSidebar.vue'
import { clearAuthSession, withAuthHeaders } from '../utils/auth'
import { buildApiUrl, parseJsonSafe } from '../utils/api'
import { getCurrentTheme, toggleTheme } from '../utils/theme'
import { getWorkspaceFeatureKeyByPath } from '../utils/workspace'

const router = useRouter()
const route = useRoute()
const themeMode = ref(getCurrentTheme())
const activeFeature = computed(() => getWorkspaceFeatureKeyByPath(route.path))

const keyword = ref('')
const riskFilter = ref('all')
const queryLoading = ref(false)
const queryError = ref('')
const queryRows = ref([])
const querySearched = ref(false)

const lowStockLoading = ref(false)
const lowStockError = ref('')
const lowStockRows = ref([])

const normalizeStock = (row) => {
  const value = Number(row?.currentStock ?? row?.stockQty ?? 0)
  return Number.isFinite(value) ? value : 0
}

const isExpiringSoon = (expiryDate) => {
  if (!expiryDate) return false
  const date = new Date(expiryDate)
  if (Number.isNaN(date.getTime())) return false
  const diff = date.getTime() - Date.now()
  const days = diff / (1000 * 60 * 60 * 24)
  return days >= 0 && days <= 30
}

const classifyRisk = (row) => {
  const stock = normalizeStock(row)
  const expiringSoon = isExpiringSoon(row?.expiryDate)

  if (stock <= 5) {
    return {
      level: 3,
      label: '紧急补货',
      statusClass: 'is-red',
      riskClass: 'is-red',
      reason: '库存已经接近断货，建议立即确认补货和交付节奏。'
    }
  }

  if (expiringSoon) {
    return {
      level: 2,
      label: '临近有效期',
      statusClass: 'is-amber',
      riskClass: 'is-amber',
      reason: '库存与有效期同时承压，建议优先核查销售与流转计划。'
    }
  }

  return {
    level: 1,
    label: '重点观察',
    statusClass: 'is-blue',
    riskClass: 'is-blue',
    reason: '当前已进入低库存区间，建议安排复核并准备分析。'
  }
}

const rankedLowStockRows = computed(() => {
  return lowStockRows.value
    .map((row) => {
      const risk = classifyRisk(row)
      return {
        ...row,
        riskLevel: risk.level,
        riskLabel: risk.label,
        statusClass: risk.statusClass,
        riskClass: risk.riskClass,
        riskReason: risk.reason,
        actionLabel: risk.label === '紧急补货'
          ? '优先联系供应商'
          : risk.label === '临近有效期'
            ? '核查流转与时效'
            : '转入分析助手'
      }
    })
    .sort((a, b) => {
      if (b.riskLevel !== a.riskLevel) return b.riskLevel - a.riskLevel
      return normalizeStock(a) - normalizeStock(b)
    })
    .slice(0, 8)
})

const filteredLowStockRows = computed(() => {
  if (riskFilter.value === 'urgent') {
    return rankedLowStockRows.value.filter((item) => item.riskLabel === '紧急补货')
  }
  if (riskFilter.value === 'expiry') {
    return rankedLowStockRows.value.filter((item) => item.riskLabel === '临近有效期')
  }
  return rankedLowStockRows.value
})

const urgentCount = computed(() => rankedLowStockRows.value.filter((item) => item.riskLabel === '紧急补货').length)
const expiringCount = computed(() => rankedLowStockRows.value.filter((item) => item.riskLabel === '临近有效期').length)
const prioritySupplierCount = computed(() => new Set(rankedLowStockRows.value.map((item) => item.supplierName).filter(Boolean)).size)

const boardStatus = computed(() => {
  if (lowStockLoading.value) {
    return {
      label: '风险同步中',
      detail: '正在刷新今日风险商品与处置清单。',
      tone: 'default'
    }
  }

  if (lowStockError.value) {
    return {
      label: '看板刷新失败',
      detail: lowStockError.value,
      tone: 'alert'
    }
  }

  if (urgentCount.value > 0) {
    return {
      label: `${urgentCount.value} 条紧急补货`,
      detail: '建议先处理紧急补货商品，再联动分析助手生成补货建议。',
      tone: 'alert'
    }
  }

  if (rankedLowStockRows.value.length > 0) {
    return {
      label: `${rankedLowStockRows.value.length} 条待观察风险`,
      detail: '当前存在低库存风险，建议按清单逐条核查。',
      tone: 'default'
    }
  }

  return {
    label: '库存风险平稳',
    detail: '当前没有低库存风险商品，可以补充做人工检索。',
    tone: 'default'
  }
})

const boardNarrative = computed(() => {
  if (urgentCount.value > 0) return '先补货，再分析'
  if (expiringCount.value > 0) return '关注时效压力'
  if (rankedLowStockRows.value.length > 0) return '逐条复核风险'
  return '适合做例行巡检'
})

const nextAction = computed(() => {
  if (urgentCount.value > 0) {
    return '优先打开“生成处理建议”，确认补货、供应商沟通和流程动作。'
  }
  if (expiringCount.value > 0) {
    return '先核查临近有效期商品的流转节奏，再决定是否需要促销或调拨。'
  }
  if (rankedLowStockRows.value.length > 0) {
    return '从优先处理清单开始，查看明细后再进入分析助手继续追问。'
  }
  return '当前风险较低，可使用 SKU 辅助检索做人工抽查或准备下一轮巡检。'
})

const handleThemeToggle = () => {
  themeMode.value = toggleTheme()
}

const selectFeature = (feature) => {
  if (!feature?.route || feature.route === route.path) return
  router.push(feature.route)
}

const prefillKeyword = (value) => {
  keyword.value = value || ''
}

const analyzeLowStock = (row) => {
  const productName = row?.productName || '未命名商品'
  const currentStock = row?.currentStock ?? row?.stockQty ?? '未知'
  const productId = row?.productId ? `，商品编号 ${row.productId}` : ''
  const supplier = row?.supplierName ? `，供应商 ${row.supplierName}` : ''
  const query = `请根据商品“${productName}”${productId}${supplier}，当前库存 ${currentStock}，分析低库存风险，并给出补货、供应商沟通和流程处理建议。`
  router.push({ path: '/analysis', query: { query } })
}

const forceLogout = () => {
  clearAuthSession()
  router.replace('/login')
}

const queryByName = async () => {
  const value = keyword.value.trim()
  if (!value) {
    queryError.value = '请输入商品名称'
    return
  }

  queryLoading.value = true
  queryError.value = ''
  querySearched.value = true

  try {
    const res = await fetch(buildApiUrl('/api/tools/query_stock', { productName: value }), {
      headers: withAuthHeaders()
    })

    if (res.status === 401) {
      forceLogout()
      return
    }

    const data = await parseJsonSafe(res)
    if (!res.ok) {
      queryError.value = data?.message || '查询失败'
      queryRows.value = []
      return
    }

    queryRows.value = Array.isArray(data?.data) ? data.data : []
  } catch {
    queryError.value = '查询接口不可用'
    queryRows.value = []
  } finally {
    queryLoading.value = false
  }
}

const loadLowStock = async () => {
  lowStockLoading.value = true
  lowStockError.value = ''

  try {
    const res = await fetch(buildApiUrl('/api/tools/low_stock'), {
      headers: withAuthHeaders()
    })

    if (res.status === 401) {
      forceLogout()
      return
    }

    const data = await parseJsonSafe(res)
    if (!res.ok) {
      lowStockError.value = data?.message || '获取低库存失败'
      lowStockRows.value = []
      return
    }

    lowStockRows.value = Array.isArray(data) ? data : []
  } catch {
    lowStockError.value = '低库存接口不可用'
    lowStockRows.value = []
  } finally {
    lowStockLoading.value = false
  }
}

onMounted(() => {
  loadLowStock()
})
</script>

<style scoped>
.inventory-main {
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

.header-annotation,
.brief-label {
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

.header-pill.active,
.status-pill.is-brand {
  color: var(--brand-600);
  border-color: rgba(79, 123, 255, 0.2);
  background: rgba(79, 123, 255, 0.1);
}

.header-pill.accent,
.status-pill.is-red {
  color: #c2410c;
  border-color: rgba(194, 65, 12, 0.2);
  background: rgba(194, 65, 12, 0.1);
}

.status-pill.is-amber {
  color: var(--amber-500);
  border-color: rgba(195, 138, 44, 0.22);
  background: rgba(195, 138, 44, 0.08);
}

.status-pill.is-blue {
  color: var(--brand-600);
  border-color: rgba(79, 123, 255, 0.2);
  background: rgba(79, 123, 255, 0.1);
}

.overview-panel,
.priority-panel,
.operator-panel,
.inventory-query-panel {
  display: grid;
  gap: 18px;
  padding: 26px;
}

.overview-head,
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.overview-head h2,
.panel-head h2 {
  margin: 0;
  font-size: 22px;
  line-height: 1.2;
}

.panel-note {
  margin: 8px 0 0;
  max-width: 560px;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.65;
}

.risk-kpi-grid {
  margin-top: 4px;
}

.risk-kpi-card {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.12), transparent 30%), var(--bg-surface-2);
}

.risk-kpi-card.is-alert {
  border-color: rgba(194, 65, 12, 0.18);
  background: linear-gradient(180deg, rgba(194, 65, 12, 0.08), transparent 32%), var(--bg-surface-2);
}

.risk-kpi-card.is-amber {
  border-color: rgba(195, 138, 44, 0.18);
  background: linear-gradient(180deg, rgba(195, 138, 44, 0.08), transparent 32%), var(--bg-surface-2);
}

.inventory-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(320px, 0.85fr);
  gap: 20px;
}

.inventory-side {
  display: grid;
  gap: 20px;
}

.priority-list,
.signal-stack {
  display: grid;
  gap: 14px;
}

.priority-toolbar,
.legend-items,
.filter-actions,
.priority-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.priority-toolbar {
  justify-content: space-between;
}

.legend-card,
.filter-card {
  display: grid;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--border-subtle);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.04);
}

.legend-title {
  font-size: 12px;
  color: var(--text-tertiary);
}

.legend-pill,
.filter-chip,
.action-tag {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--border-subtle);
  font-size: 12px;
  font-weight: 600;
}

.legend-pill.is-red,
.legend-pill.is-amber,
.legend-pill.is-blue {
  background: rgba(255, 255, 255, 0.04);
}

.filter-chip {
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
}

.filter-chip.active {
  color: var(--brand-600);
  border-color: rgba(79, 123, 255, 0.2);
  background: rgba(79, 123, 255, 0.1);
}

.action-tag {
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.04);
}

.priority-card,
.signal-card,
.empty-callout {
  border: 1px solid var(--border-subtle);
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.06), transparent 24%), var(--bg-surface-2);
}

.priority-card {
  display: grid;
  gap: 14px;
  padding: 22px;
}

.priority-card.is-red {
  border-color: rgba(194, 65, 12, 0.18);
  background: linear-gradient(180deg, rgba(194, 65, 12, 0.08), transparent 26%), var(--bg-surface-2);
}

.priority-card.is-amber {
  border-color: rgba(195, 138, 44, 0.18);
  background: linear-gradient(180deg, rgba(195, 138, 44, 0.08), transparent 26%), var(--bg-surface-2);
}

.priority-card.is-blue {
  border-color: rgba(79, 123, 255, 0.18);
  background: linear-gradient(180deg, rgba(79, 123, 255, 0.08), transparent 26%), var(--bg-surface-2);
}

.priority-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.priority-card-head strong,
.signal-card strong {
  display: block;
  font-size: 18px;
  color: var(--text-primary);
}

.priority-card-head p,
.signal-card p {
  margin: 6px 0 0;
  font-size: 14px;
  line-height: 1.65;
  color: var(--text-secondary);
}

.priority-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.priority-metrics div {
  display: grid;
  gap: 6px;
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.04);
}

.priority-metrics span {
  font-size: 12px;
  color: var(--text-tertiary);
}

.priority-metrics strong {
  font-size: 15px;
  color: var(--text-primary);
}

.signal-card {
  padding: 18px;
}

.signal-card.muted {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.04), transparent 24%), var(--bg-surface-3);
}

.query-bar {
  display: flex;
}

.table-shell {
  overflow: auto;
  border: 1px solid var(--border-subtle);
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.06), transparent 20%), var(--bg-surface-2);
}

.compact-shell {
  max-height: 320px;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
}

.data-table th,
.data-table td {
  padding: 14px 16px;
  border-bottom: 1px solid var(--border-subtle);
  text-align: left;
  font-size: 14px;
}

.data-table th {
  position: sticky;
  top: 0;
  background: var(--bg-surface-2);
  color: var(--text-secondary);
  font-weight: 600;
}

.empty-callout {
  padding: 16px 18px;
  color: var(--text-secondary);
  line-height: 1.7;
}

@media (max-width: 1024px) {
  .inventory-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 820px) {
  .inventory-shell {
    grid-template-columns: 1fr;
    gap: 14px;
  }

  .inventory-shell :deep(.workspace-sidebar) {
    position: static;
    order: 1;
    height: auto;
  }

  .inventory-main {
    order: 2;
  }

  .ops-header,
  .overview-head,
  .panel-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .ops-header-actions {
    width: 100%;
  }

  .header-badges,
  .priority-actions,
  .priority-metrics {
    width: 100%;
  }

  .priority-metrics {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .inventory-shell {
    gap: 12px;
  }

  .ops-header,
  .overview-panel,
  .priority-panel,
  .operator-panel,
  .inventory-query-panel {
    padding: 18px;
    border-radius: 20px;
  }

  .data-table th,
  .data-table td {
    padding: 12px;
  }
}
</style>
