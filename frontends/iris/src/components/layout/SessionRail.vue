<script setup lang="ts">
// Iris Phase 2 Stage 2.3 — the left session rail.
//
// A persistent column listing the caller's sessions (GET /v1/sessions), active
// one highlighted, "+ New" at top. Selecting a row switches + hydrates the
// conversation from the server (useAgentSession.switchSession). Sibling of the
// chat workspace inside AgentView — NOT a dockview pane.
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { useAgentSession } from '@/composables/useAgentSession'

const { t } = useI18n()
const session = useAgentSession()

const sessions = computed(() => session.sessions.value)
const activeId = computed(() => session.sessionId.value)

const onSelect = async (id: string) => {
  if (id === activeId.value) return
  await session.switchSession(id)
  void session.loadSessions()
}

const onNew = async () => {
  await session.startNewSession()
  void session.loadSessions()
}

const onUndoReset = async () => {
  await session.undoLastReset()
}

const titleOf = (title: string) => (title && title.trim() ? title : t('sessions.untitled'))

onMounted(() => {
  void session.loadSessions()
})
</script>

<template>
  <aside
    class="session-rail flex h-full flex-col border-r"
    style="border-color: var(--p-surface-200); background-color: var(--p-surface-50);"
    :aria-label="t('sessions.title')"
  >
    <div class="flex items-center justify-between px-3 py-2 border-b" style="border-color: var(--p-surface-200);">
      <span class="text-xs font-semibold uppercase tracking-wide" style="color: var(--p-surface-500);">
        {{ t('sessions.title') }}
      </span>
      <Button
        text
        plain
        rounded
        size="small"
        class="new-session-btn"
        :aria-label="t('sessions.newAria')"
        @click="onNew"
      >
        <i class="pi pi-plus" aria-hidden="true"></i>
      </Button>
    </div>

    <div class="flex-1 overflow-y-auto py-1">
      <p
        v-if="sessions.length === 0"
        class="px-3 py-2 text-xs"
        style="color: var(--p-surface-400);"
      >
        {{ t('sessions.empty') }}
      </p>
      <button
        v-for="s in sessions"
        :key="s.sessionId"
        type="button"
        class="session-row w-full text-left px-3 py-2"
        :class="{ 'session-row--active': s.sessionId === activeId }"
        :aria-current="s.sessionId === activeId ? 'true' : undefined"
        @click="onSelect(s.sessionId)"
      >
        <span class="session-title block truncate text-sm">{{ titleOf(s.title) }}</span>
        <span class="session-meta block text-xs" style="color: var(--p-surface-400);">
          {{ t('sessions.turnCount', { count: s.turnCount }) }}
        </span>
      </button>
    </div>

    <div
      v-if="session.lastResetUndoable.value"
      class="undo-bar px-3 py-2 border-t"
      style="border-color: var(--p-surface-200);"
    >
      <Button
        text
        size="small"
        class="undo-reset-btn w-full"
        :aria-label="t('sessions.undoResetAria')"
        @click="onUndoReset"
      >
        <i class="pi pi-undo mr-2" aria-hidden="true"></i>
        <span class="text-xs">{{ t('sessions.undoReset') }}</span>
      </Button>
    </div>
  </aside>
</template>

<style scoped>
.session-rail {
  width: 14rem;
  flex-shrink: 0;
}
.new-session-btn {
  width: 1.75rem;
  height: 1.75rem;
  padding: 0 !important;
}
.session-row {
  border: none;
  background: transparent;
  cursor: pointer;
  border-radius: 0.375rem;
  transition: background-color 120ms ease;
}
.session-row:hover {
  background-color: var(--p-surface-100);
}
.session-row--active,
.session-row--active:hover {
  background-color: var(--p-primary-50);
}
.session-row--active .session-title {
  color: var(--p-primary-700);
  font-weight: 600;
}
.undo-reset-btn {
  justify-content: flex-start !important;
}
</style>
