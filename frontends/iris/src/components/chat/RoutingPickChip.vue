<script setup lang="ts">
// Iris Phase 3 Stage 3.2 T5 — RoutingPickChip.
//
// Rendered when Themis returns needs_user_pick: the BFF emits one
// envelope/v1 RoutingPickChip per candidate agent (label = capabilities-mcp
// display name; why = the AgentAlternate.why string). Clicking re-issues the
// original question pinned to that agent (routing_hint, Layer-0 through
// Themis) — the parent owns the re-issue (it holds the question text).
import Chip from 'primevue/chip'
import type { RoutingPickChip } from '@/types/envelope'

const props = defineProps<{ chip: RoutingPickChip }>()

const emit = defineEmits<{
  (e: 'pick', agentId: string, label: string): void
}>()

const onPick = () => {
  const agentId = props.chip.agentId?.value ?? ''
  if (!agentId) return
  emit('pick', agentId, props.chip.label)
}
</script>

<template>
  <Chip
    class="routing-pick-chip"
    tabindex="0"
    :title="chip.why"
    role="button"
    :aria-label="`${chip.label} — ${chip.why}`"
    @click="onPick"
    @keydown.enter="onPick"
    @keydown.space.prevent="onPick"
  >
    <span class="routing-pick-icon pi pi-directions" aria-hidden="true" />
    <span class="routing-pick-body">
      <span class="routing-pick-label">{{ chip.label }}</span>
      <span v-if="chip.why" class="routing-pick-why">{{ chip.why }}</span>
    </span>
  </Chip>
</template>

<style scoped>
.routing-pick-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  cursor: pointer;
  padding: 0.3rem 0.65rem;
  background-color: var(--p-primary-50);
  border: 1px solid var(--p-primary-200);
  color: var(--p-primary-800);
  transition: background-color 150ms ease, border-color 150ms ease;
}
.routing-pick-chip:hover,
.routing-pick-chip:focus-visible {
  background-color: var(--p-primary-100);
  border-color: var(--p-primary-400);
  outline: none;
}
.routing-pick-icon {
  font-size: 0.85rem;
  color: var(--p-primary-500);
}
.routing-pick-body {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}
.routing-pick-label {
  font-size: 0.78rem;
  font-weight: 600;
}
.routing-pick-why {
  font-size: 0.68rem;
  color: var(--p-primary-600);
}
</style>
