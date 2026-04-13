<template>
  <aside class="workspace-sidebar">
    <div class="sidebar-brand">
      <div class="brand-mark">{{ mark }}</div>
      <div class="brand-copy">
        <p class="brand-eyebrow">{{ eyebrow }}</p>
        <h2>{{ title }}</h2>
        <p>{{ description }}</p>
      </div>
    </div>

    <button v-if="showCreate" class="new-chat-btn" type="button" @click="$emit('create')">
      <span>+</span>
      <span>{{ createLabel }}</span>
    </button>

    <section class="sidebar-nav-section">
      <div class="session-title">功能区</div>
      <div class="sidebar-nav-grid">
        <button
          v-for="feature in WORKSPACE_FEATURES"
          :key="feature.key"
          type="button"
          :class="['sidebar-nav-item', activeFeature === feature.key ? 'active' : '']"
          @click="$emit('feature-select', feature)"
        >
          <span class="sidebar-token">{{ feature.badge }}</span>
          <span class="sidebar-nav-label">{{ feature.label }}</span>
        </button>
      </div>
    </section>

    <section v-if="sessionTitle" class="session-list">
      <div class="session-title">{{ sessionTitle }}</div>
      <div class="session-list-scroll">
        <button
          v-for="session in sessions"
          :key="session.id"
          type="button"
          :class="['session-item', session.id === currentSessionId ? 'active' : '']"
          @click="$emit('session-select', session.id)"
        >
          <span class="session-icon">{{ resolveSessionBadge() }}</span>
          <span class="session-info">
            <span class="session-name">{{ session.name || '新会话' }}</span>
            <span class="session-meta">{{ (session.messages || []).length }} 条消息</span>
          </span>
          <span class="session-delete" @click.stop="$emit('session-delete', session.id)">×</span>
        </button>
      </div>
    </section>
  </aside>
</template>

<script setup>
import { WORKSPACE_FEATURES, getWorkspaceFeatureBadge } from '../utils/workspace'

const props = defineProps({
  eyebrow: {
    type: String,
    default: ''
  },
  title: {
    type: String,
    default: ''
  },
  description: {
    type: String,
    default: ''
  },
  mark: {
    type: String,
    default: 'AI'
  },
  showCreate: {
    type: Boolean,
    default: false
  },
  createLabel: {
    type: String,
    default: '新建'
  },
  activeFeature: {
    type: String,
    default: 'chat'
  },
  sessionTitle: {
    type: String,
    default: ''
  },
  sessions: {
    type: Array,
    default: () => []
  },
  currentSessionId: {
    type: String,
    default: ''
  },
  sessionBadge: {
    type: String,
    default: ''
  }
})

defineEmits(['create', 'feature-select', 'session-select', 'session-delete'])

const resolveSessionBadge = () => props.sessionBadge || getWorkspaceFeatureBadge(props.activeFeature)
</script>

<style scoped>
.workspace-sidebar {
  position: sticky;
  top: 20px;
  height: calc(100vh - 40px);
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 22px;
  border: 1px solid var(--border-subtle);
  border-radius: 28px;
  overflow: hidden;
  background:
    radial-gradient(circle at top right, rgba(79, 123, 255, 0.12), transparent 30%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.08), transparent 24%),
    var(--bg-surface-1);
  backdrop-filter: blur(22px);
  box-shadow: var(--shadow-md);
}

.workspace-sidebar::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(140deg, rgba(255, 255, 255, 0.07), transparent 28%),
    radial-gradient(circle at 12% 12%, rgba(219, 186, 119, 0.08), transparent 24%);
}

.workspace-sidebar::after {
  content: '';
  position: absolute;
  left: 24px;
  right: 24px;
  top: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--border-ornament), transparent);
  pointer-events: none;
}

.sidebar-brand {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.brand-mark {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 16px;
  color: var(--text-inverse);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.16), transparent 56%),
    linear-gradient(135deg, var(--brand-500), var(--teal-500));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.18),
    0 16px 30px rgba(31, 63, 145, 0.22);
  font-family: var(--font-label);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.12em;
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

.new-chat-btn {
  min-height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 16px;
  position: relative;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 16px;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-500), var(--brand-600));
  box-shadow: 0 18px 34px rgba(38, 68, 152, 0.24);
  cursor: pointer;
  font-weight: 600;
  transition:
    transform var(--duration-fast) var(--ease-standard),
    background var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.new-chat-btn::before {
  content: '';
  position: absolute;
  inset: 1px;
  border-radius: inherit;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.22), transparent 54%);
  pointer-events: none;
}

.new-chat-btn:hover {
  background: linear-gradient(135deg, var(--brand-600), var(--brand-700));
}

.sidebar-nav-section,
.session-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.session-list {
  flex: 1;
  min-height: 0;
}

.session-title {
  margin: 0;
  font-family: var(--font-label);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--text-tertiary);
}

.sidebar-nav-grid,
.session-list-scroll {
  display: grid;
  gap: 8px;
}

.session-list-scroll {
  flex: 1;
  min-height: 0;
  grid-auto-rows: 92px;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-gutter: stable;
}

.session-list-scroll::-webkit-scrollbar {
  width: 8px;
}

.session-list-scroll::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(100, 116, 139, 0.22);
}

.session-list-scroll::-webkit-scrollbar-thumb:hover {
  background: rgba(100, 116, 139, 0.34);
}

.sidebar-nav-item,
.session-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid transparent;
  border-radius: 16px;
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.01);
  cursor: pointer;
  text-align: left;
  transition:
    transform var(--duration-fast) var(--ease-standard),
    background var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.session-item {
  height: 100%;
}

.sidebar-nav-item:hover,
.session-item:hover {
  border-color: var(--border-ornament);
  background: var(--bg-surface-3);
  color: var(--text-primary);
}

.sidebar-nav-item.active,
.session-item.active {
  color: var(--text-primary);
  border-color: rgba(79, 123, 255, 0.22);
  background: linear-gradient(135deg, rgba(79, 123, 255, 0.12), rgba(30, 168, 153, 0.06));
  box-shadow:
    inset 2px 0 0 var(--brand-500),
    0 10px 22px rgba(17, 29, 58, 0.08);
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
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.14), transparent 74%), var(--bg-surface-2);
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
  font-family: var(--font-label);
}

.sidebar-nav-label {
  font-size: 14px;
  font-weight: 600;
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

@media (max-width: 820px) {
  .workspace-sidebar {
    position: static;
    height: auto;
  }
}
</style>
