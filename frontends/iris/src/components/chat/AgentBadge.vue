<script setup lang="ts">
// Iris Phase 3 Stage 3.2 T6 (FE) — agent badge + re-ask picker (PD-14).
//
// Every answered bubble carries a persistent "Answered by {agent}" badge. When
// re-ask candidates exist the badge opens a picker: clicking a candidate
// re-routes the turn pinned to it (reask_agent typed action → the BFF records
// the corrected_agent_id misroute label). Candidates are pre-sorted by the
// original RoutingDecision.alternates (each carrying its `why`); the parent
// supplies the ordered list.
import { ref } from 'vue'
import Popover from 'primevue/popover'
import { useI18n } from 'vue-i18n'

export interface ReaskCandidate {
  agentId: string
  label: string
  why?: string
}

const props = defineProps<{ label: string; candidates: ReaskCandidate[] }>()

const emit = defineEmits<{
  (e: 'reask', agentId: string): void
}>()

const { t } = useI18n()
const pop = ref<InstanceType<typeof Popover> | null>(null)
// Reflect the popover's open/closed state for `aria-expanded` (Popover doesn't
// expose its visibility as a prop, so track it via its show/hide events).
const open = ref(false)

const toggle = (event: Event) => {
  if (props.candidates.length > 0) pop.value?.toggle(event)
}

const pick = (agentId: string) => {
  pop.value?.hide()
  emit('reask', agentId)
}
</script>

<template>
  <div class="agent-badge-wrap">
    <button
      type="button"
      class="agent-badge"
      :class="{ clickable: candidates.length > 0 }"
      :aria-haspopup="candidates.length > 0 ? 'menu' : undefined"
      :aria-expanded="candidates.length > 0 ? open : undefined"
      @click="toggle"
    >
      <span class="pi pi-user agent-badge-icon" aria-hidden="true" />
      <span class="agent-badge-text">{{ t('chat.reask.badge', { agent: label }) }}</span>
      <span v-if="candidates.length > 0" class="pi pi-angle-down agent-badge-caret" aria-hidden="true" />
    </button>

    <Popover ref="pop" @show="open = true" @hide="open = false">
      <div class="reask-menu" role="menu" :aria-label="t('chat.reask.menuTitle')">
        <div class="reask-menu-title">{{ t('chat.reask.menuTitle') }}</div>
        <button
          v-for="c in candidates"
          :key="c.agentId"
          type="button"
          class="reask-item"
          role="menuitem"
          @click="pick(c.agentId)"
        >
          <span class="reask-item-label">{{ c.label }}</span>
          <span v-if="c.why" class="reask-item-why">{{ c.why }}</span>
        </button>
      </div>
    </Popover>
  </div>
</template>

<style scoped>
.agent-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.1rem 0.45rem;
  font-size: 0.7rem;
  color: var(--p-surface-600);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 0.5rem;
  cursor: default;
}
.agent-badge.clickable {
  cursor: pointer;
  border-color: var(--p-surface-200);
}
.agent-badge.clickable:hover {
  background-color: var(--p-surface-50);
  border-color: var(--p-surface-300);
  color: var(--p-surface-800);
}
.agent-badge-icon {
  font-size: 0.7rem;
}
.reask-menu {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  min-width: 12rem;
  padding: 0.1rem;
}
.reask-menu-title {
  font-size: 0.72rem;
  font-weight: 600;
  color: var(--p-surface-700);
  padding: 0.2rem 0.4rem;
}
.reask-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  text-align: left;
  gap: 0.05rem;
  padding: 0.35rem 0.5rem;
  border: none;
  border-radius: 0.4rem;
  background: transparent;
  cursor: pointer;
}
.reask-item:hover {
  background-color: var(--p-primary-50);
}
.reask-item-label {
  font-size: 0.78rem;
  font-weight: 600;
  color: var(--p-surface-800);
}
.reask-item-why {
  font-size: 0.68rem;
  color: var(--p-surface-500);
}
</style>
