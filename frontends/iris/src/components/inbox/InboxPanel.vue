<script setup lang="ts">
// Iris Phase 4 Stage 4.1 — investigation inbox panel (PD-2). Rows of {question,
// status, origin, cost, session link}, live via the inbox store's SSE stream.
// Selecting a row reveals its debug-grade hypothesis tree. Needs-input rows route
// to the existing chat clarification flow (Pythia-arc control proxy); at Phase 4
// the inbox is typically empty (fake Pythia).
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { useInboxStore } from '@/stores/inboxStore'
import { useAgentSession } from '@/composables/useAgentSession'
import HypothesisTree, { type Hypothesis } from './HypothesisTree.vue'
import type { InboxItem } from '@/services/inbox'

const store = useInboxStore()
const { view } = storeToRefs(store)
const { t } = useI18n()
const session = useAgentSession()

const selectedId = ref<string | null>(null)
// Hypotheses for the selected investigation arrive via Pythia's artifact stream
// (Pythia arc). At Phase 4 there is no live source, so this stays empty.
const selectedHypotheses = ref<Hypothesis[]>([])

const statusClass = (s: string) => `inbox-status--${s.toLowerCase()}`

const onSelect = (item: InboxItem) => {
  selectedId.value = selectedId.value === item.investigationId ? null : item.investigationId
}

const onOpenSession = async (item: InboxItem) => {
  if (item.sessionId) await session.switchSession(item.sessionId)
}

onMounted(() => {
  void store.load()
  store.startStream()
})
onBeforeUnmount(() => store.stopStream())
</script>

<template>
  <div class="inbox-panel">
    <h3 class="inbox-title">{{ t('inbox.title') }}</h3>
    <p v-if="view.items.length === 0" class="inbox-empty">{{ t('inbox.empty') }}</p>
    <ul v-else class="inbox-rows">
      <li v-for="item in view.items" :key="item.investigationId" class="inbox-row">
        <button type="button" class="inbox-row-main" @click="onSelect(item)">
          <span class="inbox-row-q">{{ item.question }}</span>
          <span class="inbox-row-meta">
            <span class="inbox-status" :class="statusClass(item.status)">
              {{ t(`inbox.status.${item.status}`) }}
            </span>
            <span class="inbox-origin">{{ item.origin }}</span>
            <span v-if="item.costSoFar > 0" class="inbox-cost">
              {{ t('inbox.cost', { cost: item.costSoFar.toFixed(2) }) }}
            </span>
          </span>
        </button>
        <Button
          v-if="item.sessionId"
          text
          size="small"
          icon="pi pi-arrow-right"
          :aria-label="t('inbox.open')"
          v-tooltip.left="t('inbox.open')"
          @click="onOpenSession(item)"
        />
        <div v-if="selectedId === item.investigationId" class="inbox-tree">
          <HypothesisTree :hypotheses="selectedHypotheses" />
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.inbox-panel {
  padding: 0.75rem;
  overflow: auto;
  height: 100%;
}
.inbox-title {
  font-size: 0.95rem;
  font-weight: 600;
  margin: 0 0 0.6rem 0;
}
.inbox-empty {
  font-size: 0.8rem;
  color: var(--p-surface-500);
  font-style: italic;
}
.inbox-rows {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.inbox-row {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 0.3rem;
  border: 1px solid var(--p-surface-200);
  border-radius: 0.6rem;
  padding: 0.45rem 0.55rem;
}
.inbox-row-main {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.2rem;
  background: transparent;
  border: none;
  cursor: pointer;
  text-align: left;
}
.inbox-row-q {
  font-size: 0.82rem;
  font-weight: 600;
}
.inbox-row-meta {
  display: inline-flex;
  gap: 0.5rem;
  font-size: 0.7rem;
  color: var(--p-surface-500);
}
.inbox-status {
  padding: 0 0.3rem;
  border-radius: 0.3rem;
  background: var(--p-surface-100);
}
.inbox-status--needs_input {
  color: var(--p-amber-700, #b45309);
}
.inbox-status--running {
  color: var(--p-primary-700);
}
.inbox-status--failed {
  color: var(--p-red-700, #b91c1c);
}
.inbox-tree {
  grid-column: 1 / -1;
  margin-top: 0.4rem;
  padding-top: 0.4rem;
  border-top: 1px dashed var(--p-surface-200);
}
</style>
