<template>
  <div class="workspace-shell workflow-shell">
    <WorkspaceSidebar
      eyebrow="Process Guidance"
      title="流程助手"
      description="把入库、出库和异常处理从静态列表升级成可扫读的流程板。"
      mark="OPS"
      :active-feature="activeFeature"
      @feature-select="selectFeature"
    />
    <main class="workspace-main workflow-main">
      <header class="ops-header surface-panel">
      <div class="ops-header-copy">
        <p class="brand-eyebrow">Process Guidance</p>
        <h1>流程助手</h1>
        <p>把入库、出库和异常处理从静态列表升级成可扫读的流程板。</p>
      </div>

      <div class="ops-header-actions">
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

    <section class="workflow-grid">
      <aside class="surface-panel workflow-nav">
        <p class="brand-eyebrow">流程分类</p>
        <div class="workflow-tab-list">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            type="button"
            :class="['workflow-tab', activeTab === tab.key ? 'active' : '']"
            @click="activeTab = tab.key"
          >
            <strong>{{ tab.label }}</strong>
            <span>{{ tab.desc }}</span>
          </button>
        </div>
      </aside>

      <section class="surface-panel workflow-board">
        <div class="panel-head">
          <div>
            <p class="brand-eyebrow">Execution Board</p>
            <h2>{{ currentTab.label }}</h2>
          </div>
          <span class="status-pill is-brand">{{ currentTab.steps.length }} 个阶段</span>
        </div>

        <div class="workflow-timeline">
          <article v-for="(step, index) in currentTab.steps" :key="step.title" class="workflow-card">
            <div class="workflow-step-index">{{ index + 1 }}</div>
            <div class="workflow-step-body">
              <div class="workflow-step-head">
                <h3>{{ step.title }}</h3>
                <span :class="['status-pill', step.tone]">{{ step.badge }}</span>
              </div>
              <p class="workflow-step-desc">{{ step.desc }}</p>
              <div class="workflow-chip-row">
                <span class="workflow-chip">风险：{{ step.risk }}</span>
                <span class="workflow-chip">动作：{{ step.action }}</span>
              </div>
            </div>
          </article>
        </div>
      </section>
    </section>
    </main>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkspaceSidebar from '../components/WorkspaceSidebar.vue'
import { getCurrentTheme, toggleTheme } from '../utils/theme'
import { getWorkspaceFeatureKeyByPath } from '../utils/workspace'

const router = useRouter()
const route = useRoute()
const themeMode = ref(getCurrentTheme())
const activeTab = ref('inbound')
const activeFeature = computed(() => getWorkspaceFeatureKeyByPath(route.path))

const tabs = [
  {
    key: 'inbound',
    label: '入库流程',
    desc: '收货、质检、上架与抽盘',
    steps: [
      {
        title: '收货校验',
        desc: '核对采购单、到货数量和批次信息，发现差异立即标注。',
        risk: '数量或批次不一致',
        action: '冻结异常记录并通知采购',
        badge: '高频',
        tone: 'is-brand'
      },
      {
        title: '质检与隔离',
        desc: '先执行质检，再决定是否进入正式库位；不合格商品进入隔离区。',
        risk: '未检先上架',
        action: '建立隔离库位并记录处理人',
        badge: '关键',
        tone: 'is-amber'
      },
      {
        title: '上架复核',
        desc: '上架后进行抽样盘点，确认账实一致再完成入库闭环。',
        risk: '库位错误或账实不符',
        action: '触发抽盘与二次确认',
        badge: '闭环',
        tone: 'is-teal'
      }
    ]
  },
  {
    key: 'outbound',
    label: '出库流程',
    desc: '分配、拣货、复核与发运',
    steps: [
      {
        title: '波次分配',
        desc: '发起波次前确认库存已锁定，避免超卖和重复拣货。',
        risk: '库存未锁定',
        action: '先执行库存校验',
        badge: '前置',
        tone: 'is-brand'
      },
      {
        title: '拣货规则',
        desc: '优先按 FEFO/FIFO 规则拣货，降低临期与呆滞库存风险。',
        risk: '规则失效导致损耗',
        action: '校验效期策略与路径',
        badge: '策略',
        tone: 'is-amber'
      },
      {
        title: '发运审计',
        desc: '高价值商品进行双人复核，发运后回写运单号并生成库存扣减审计。',
        risk: '高价值商品漏审',
        action: '双人确认与日志留存',
        badge: '审计',
        tone: 'is-teal'
      }
    ]
  },
  {
    key: 'incident',
    label: '异常处理',
    desc: '库存差异、单据缺失与库龄超阈值',
    steps: [
      {
        title: '库存差异识别',
        desc: '当库存差异超过阈值时，先冻结 SKU，再安排复盘。',
        risk: '异常继续流转',
        action: '冻结 SKU 并追踪责任链',
        badge: '告警',
        tone: 'is-amber'
      },
      {
        title: '单据缺失处理',
        desc: '缺少关键单据时标记高风险并转人工审批。',
        risk: '无凭证操作',
        action: '转人工审批并补录单据',
        badge: '审批',
        tone: 'is-brand'
      },
      {
        title: '库龄超阈值',
        desc: '库龄超阈值后生成处理工单，明确调拨、促销或报废路径。',
        risk: '呆滞库存积压',
        action: '生成处理工单并跟踪结果',
        badge: '处置',
        tone: 'is-teal'
      }
    ]
  }
]

const currentTab = computed(() => tabs.find((tab) => tab.key === activeTab.value) || tabs[0])

const handleThemeToggle = () => {
  themeMode.value = toggleTheme()
}

const selectFeature = (feature) => {
  if (!feature?.route || feature.route === route.path) return
  router.push(feature.route)
}
</script>

<style scoped>
.workflow-main {
  display: grid;
  gap: 20px;
  min-width: 0;
}

.ops-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 24px;
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

.ops-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.workflow-grid {
  display: grid;
  grid-template-columns: minmax(260px, 0.7fr) minmax(0, 1.3fr);
  gap: 20px;
}

.workflow-nav,
.workflow-board {
  display: grid;
  gap: 16px;
  padding: 24px;
}

.workflow-tab-list {
  display: grid;
  gap: 10px;
}

.workflow-tab {
  display: grid;
  gap: 6px;
  padding: 16px;
  border: 1px solid var(--border-subtle);
  border-radius: 18px;
  background: var(--bg-surface-2);
  cursor: pointer;
  text-align: left;
}

.workflow-tab strong {
  font-size: 15px;
  color: var(--text-primary);
}

.workflow-tab span {
  font-size: 13px;
  color: var(--text-secondary);
}

.workflow-tab.active {
  border-color: rgba(37, 99, 235, 0.18);
  background: rgba(37, 99, 235, 0.08);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.panel-head h2 {
  margin: 0;
  font-size: 22px;
}

.workflow-timeline {
  display: grid;
  gap: 14px;
}

.workflow-card {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--border-subtle);
  border-radius: 20px;
  background: var(--bg-surface-2);
}

.workflow-step-index {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-500), var(--teal-500));
  font-weight: 700;
}

.workflow-step-body {
  display: grid;
  gap: 10px;
}

.workflow-step-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.workflow-step-head h3,
.workflow-step-desc {
  margin: 0;
}

.workflow-step-desc {
  color: var(--text-secondary);
  line-height: 1.7;
}

.workflow-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.workflow-chip {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  background: var(--bg-muted);
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
}

@media (max-width: 1024px) {
  .workflow-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 820px) {
  .workflow-shell {
    grid-template-columns: 1fr;
    gap: 14px;
  }

  .workflow-shell :deep(.workspace-sidebar) {
    position: static;
    order: 1;
    height: auto;
  }

  .workflow-main {
    order: 2;
  }

  .ops-header {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (max-width: 640px) {
  .workflow-shell {
    gap: 12px;
  }

  .ops-header,
  .workflow-nav,
  .workflow-board {
    padding: 18px;
    border-radius: 20px;
  }

  .workflow-card {
    grid-template-columns: 1fr;
  }
}
</style>
