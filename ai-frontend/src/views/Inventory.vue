<template>
  <div class="workspace-shell inventory-shell">
    <WorkspaceSidebar
      eyebrow="Inventory Insight"
      title="库存洞察"
      description="把低库存预警、SKU 查询结果和操作入口整合到同一视图里。"
      mark="INV"
      :active-feature="activeFeature"
      @feature-select="selectFeature"
    />
    <main class="workspace-main inventory-main">
      <header class="ops-header surface-panel">
        <div class="ops-header-copy">
          <p class="brand-eyebrow">Inventory Insight</p>
          <h1>库存洞察</h1>
          <p>把低库存预警、SKU 查询结果和操作入口整合到同一视图里。</p>
          <div class="header-inline-meta">
            <span class="header-annotation">低库存巡检</span>
            <span class="header-dot"></span>
            <span class="header-annotation">商品检索</span>
            <span class="header-dot"></span>
            <span class="header-annotation">流程联动</span>
          </div>
        </div>

        <div class="ops-header-actions">
          <div class="header-badges">
            <span :class="['header-pill', inventoryPulse.tone === 'alert' ? 'accent' : 'active']">{{ inventoryPulse.label }}</span>
            <span class="header-pill">{{ querySummary.label }}</span>
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

      <section class="inventory-brief surface-panel">
        <article class="brief-card">
          <span class="brief-label">库存脉冲</span>
          <strong>{{ inventoryPulse.label }}</strong>
          <p>{{ inventoryPulse.detail }}</p>
        </article>
        <article class="brief-card">
          <span class="brief-label">查询状态</span>
          <strong>{{ querySummary.label }}</strong>
          <p>{{ querySummary.detail }}</p>
        </article>
      </section>

    <section class="workspace-kpi-grid">
      <article :class="['surface-card', 'workspace-kpi-card', lowStockRows.length === 0 ? 'is-muted' : '']">
        <p class="kpi-label">低库存商品</p>
        <p :class="['kpi-value', lowStockRows.length === 0 ? 'is-muted' : '']">{{ lowStockRows.length }}</p>
        <p class="kpi-meta">自动读取低库存接口返回结果</p>
      </article>
      <article :class="['surface-card', 'workspace-kpi-card', queryRows.length === 0 ? 'is-muted' : '']">
        <p class="kpi-label">已查询 SKU</p>
        <p :class="['kpi-value', queryRows.length === 0 ? 'is-muted' : '']">{{ queryRows.length }}</p>
        <p class="kpi-meta">按商品名称检索到的明细数量</p>
      </article>
      <article :class="['surface-card', 'workspace-kpi-card', supplierCount === 0 ? 'is-muted' : '']">
        <p class="kpi-label">供应商数量</p>
        <p :class="['kpi-value', supplierCount === 0 ? 'is-muted' : '']">{{ supplierCount }}</p>
        <p class="kpi-meta">查询结果中去重后的供应商数量</p>
      </article>
      <article :class="['surface-card', 'workspace-kpi-card', highestStock === 0 ? 'is-muted' : '']">
        <p class="kpi-label">最高库存</p>
        <p :class="['kpi-value', highestStock === 0 ? 'is-muted' : '']">{{ highestStock }}</p>
        <p class="kpi-meta">查询结果中的最大库存值</p>
      </article>
    </section>

    <section class="inventory-grid">
      <article class="surface-panel inventory-query-panel">
        <div class="panel-head">
          <div>
            <p class="brand-eyebrow">Product Search</p>
            <h2>按商品查询</h2>
            <p class="panel-note">输入商品名称后，可快速查看库存、供应商和 SKU 细节。</p>
          </div>
          <button class="secondary-button" type="button" @click="queryByName" :disabled="queryLoading">
            {{ queryLoading ? '查询中...' : '立即查询' }}
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

        <div v-if="queryRows.length" class="table-shell">
          <table class="data-table">
            <thead>
              <tr>
                <th>商品名称</th>
                <th>商品编号</th>
                <th>现有库存</th>
                <th>库存数量</th>
                <th>供应商</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in queryRows" :key="row.id">
                <td>{{ row.productName || '-' }}</td>
                <td>{{ row.productId || '-' }}</td>
                <td>{{ row.currentStock ?? '-' }}</td>
                <td>{{ row.stockQty ?? '-' }}</td>
                <td>{{ row.supplierName || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else-if="querySearched" class="info-banner">没有查询到数据，可以换个商品名称试试。</div>
        <div v-else class="empty-callout">输入商品名称后可快速查看库存、供应商和 SKU 明细。</div>
      </article>

      <aside class="inventory-side">
        <article class="surface-panel inventory-warning-panel">
          <div class="panel-head">
            <div>
              <p class="brand-eyebrow">Low Stock</p>
              <h2>低库存预警</h2>
              <p class="panel-note">优先处理当前预警商品，再转入分析页做连续追问。</p>
            </div>
            <button class="secondary-button" type="button" :disabled="lowStockLoading" @click="loadLowStock">
              {{ lowStockLoading ? '刷新中...' : '刷新' }}
            </button>
          </div>

          <div v-if="lowStockError" class="error-banner">{{ lowStockError }}</div>
          <div v-else-if="lowStockRows.length" class="warning-list">
            <article v-for="row in lowStockRows" :key="row.id" class="warning-card">
              <div class="warning-card-head">
                <strong>{{ row.productName || '未命名商品' }}</strong>
                <span class="status-pill is-amber">低库存</span>
              </div>
              <p>商品编号：{{ row.productId || '-' }}</p>
              <p>现有库存：{{ row.currentStock ?? '-' }}</p>
              <p>有效期：{{ row.expiryDate || '-' }}</p>
            </article>
          </div>
          <div v-else class="success-banner">当前没有低库存商品。</div>
        </article>

        <article class="surface-panel inventory-guide-panel">
          <p class="brand-eyebrow">Operator Tips</p>
          <h2>操作建议</h2>
          <ul class="guide-list">
            <li>先关注低库存商品，再进入分析页做连续追问。</li>
            <li>查询结果过多时，优先按供应商和高库存值进行人工筛查。</li>
            <li>发现异常 SKU 后可切换到流程助手，核对入库与出库流程。</li>
          </ul>
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
import { getCurrentTheme, toggleTheme } from '../utils/theme'
import { getWorkspaceFeatureKeyByPath } from '../utils/workspace'

const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
const router = useRouter()
const route = useRoute()
const themeMode = ref(getCurrentTheme())
const activeFeature = computed(() => getWorkspaceFeatureKeyByPath(route.path))

const keyword = ref('')
const queryLoading = ref(false)
const queryError = ref('')
const queryRows = ref([])
const querySearched = ref(false)

const lowStockLoading = ref(false)
const lowStockError = ref('')
const lowStockRows = ref([])

const supplierCount = computed(() => {
  return new Set(queryRows.value.map((item) => item.supplierName).filter(Boolean)).size
})

const highestStock = computed(() => {
  const max = queryRows.value.reduce((highest, item) => {
    const current = Number(item.currentStock ?? item.stockQty ?? 0)
    return Number.isFinite(current) ? Math.max(highest, current) : highest
  }, 0)
  return max || 0
})

const inventoryPulse = computed(() => {
  if (lowStockLoading.value) {
    return {
      label: '预警同步中',
      detail: '正在刷新低库存商品与库存概览。',
      tone: 'default'
    }
  }

  if (lowStockError.value) {
    return {
      label: '预警获取失败',
      detail: lowStockError.value,
      tone: 'alert'
    }
  }

  if (lowStockRows.value.length > 0) {
    return {
      label: `${lowStockRows.value.length} 条库存预警`,
      detail: '建议优先处理低库存商品，再联动分析页继续追问。',
      tone: 'alert'
    }
  }

  return {
    label: '库存状态平稳',
    detail: '当前没有低库存商品，适合继续做 SKU 查询或人工筛查。',
    tone: 'default'
  }
})

const querySummary = computed(() => {
  if (queryLoading.value) {
    return {
      label: '查询中',
      detail: '正在按商品名称检索库存与供应商信息。'
    }
  }

  if (queryError.value) {
    return {
      label: '查询失败',
      detail: queryError.value
    }
  }

  if (queryRows.value.length > 0) {
    return {
      label: `${queryRows.value.length} 条结果`,
      detail: `已识别 ${supplierCount.value} 个供应商，最高库存 ${highestStock.value}。`
    }
  }

  if (querySearched.value) {
    return {
      label: '暂无匹配结果',
      detail: '可以换一个商品名称，或先从低库存预警开始排查。'
    }
  }

  return {
    label: '等待查询',
    detail: '输入商品名称后，这里会汇总 SKU、供应商和库存概览。'
  }
})

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
    const res = await fetch(`${API_BASE}/api/tools/query_stock?productName=${encodeURIComponent(value)}`, {
      headers: withAuthHeaders()
    })

    if (res.status === 401) {
      forceLogout()
      return
    }

    const data = await res.json().catch(() => null)
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
    const res = await fetch(`${API_BASE}/api/tools/low_stock`, {
      headers: withAuthHeaders()
    })

    if (res.status === 401) {
      forceLogout()
      return
    }

    const data = await res.json().catch(() => null)
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
  padding: 24px 26px;
}

.ops-header-copy h1,
.ops-header-copy p {
  margin: 0;
}

.ops-header-copy h1 {
  font-size: clamp(28px, 3vw, 36px);
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
  color: var(--amber-500);
  border-color: rgba(195, 138, 44, 0.22);
  background: rgba(195, 138, 44, 0.08);
}

.inventory-brief {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  padding: 18px 20px;
}

.brief-card {
  display: grid;
  gap: 6px;
  padding: 16px 18px;
  border: 1px solid var(--border-subtle);
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.08), transparent 26%), var(--bg-surface-2);
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

.inventory-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(320px, 0.8fr);
  gap: 20px;
}

.inventory-side {
  display: grid;
  gap: 20px;
}

.inventory-query-panel,
.inventory-warning-panel,
.inventory-guide-panel {
  display: grid;
  gap: 18px;
  padding: 24px;
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
  line-height: 1.2;
}

.panel-note {
  margin: 8px 0 0;
  max-width: 520px;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.65;
}

.workspace-kpi-card.is-muted {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.04), transparent 26%), var(--bg-surface-2);
}

.kpi-value.is-muted {
  opacity: 0.58;
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
  border: 1px dashed var(--border-subtle);
  border-radius: 18px;
  color: var(--text-secondary);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.06), transparent 24%), var(--bg-surface-2);
  line-height: 1.7;
}

.warning-list {
  display: grid;
  gap: 12px;
}

.warning-card {
  display: grid;
  gap: 8px;
  padding: 18px;
  border: 1px solid rgba(217, 119, 6, 0.14);
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.06), transparent 24%), rgba(195, 138, 44, 0.07);
}

.warning-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.warning-card p {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.guide-list {
  margin: 0;
  padding-left: 18px;
  display: grid;
  gap: 10px;
  color: var(--text-secondary);
  line-height: 1.7;
}

@media (max-width: 1024px) {
  .inventory-brief,
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

  .ops-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .ops-header-actions {
    width: 100%;
  }

  .header-badges {
    width: 100%;
  }
}

@media (max-width: 640px) {
  .inventory-shell {
    gap: 12px;
  }

  .ops-header,
  .inventory-query-panel,
  .inventory-warning-panel,
  .inventory-guide-panel {
    padding: 18px;
    border-radius: 20px;
  }

  .data-table th,
  .data-table td {
    padding: 12px;
  }
}
</style>
