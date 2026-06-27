<script setup lang="ts">
// Iris Phase 3 Stage 3.2 T7 — InvestigateChip (PD-1).
//
// Escalation affordance: click re-issues the turn with routing_hint = "pythia"
// and the embedded HandoffContext. Two emitters carry it on the envelope —
// Golem (confidence-gate failure with analytical intent) and the BFF itself
// (always-on "Investigate this" on a table/chart block). The parent owns the
// dispatch (the `investigate` typed action, which names the turn it escalates).
import Chip from 'primevue/chip'
import type { InvestigateChip } from '@/types/envelope'

const props = defineProps<{ chip: InvestigateChip }>()

const emit = defineEmits<{
  (e: 'investigate', chip: InvestigateChip): void
}>()

const onClick = () => emit('investigate', props.chip)
</script>

<template>
  <Chip
    class="investigate-chip"
    tabindex="0"
    role="button"
    :title="chip.proposedQuestion"
    :aria-label="chip.label || chip.proposedQuestion"
    @click="onClick"
    @keydown.enter="onClick"
    @keydown.space.prevent="onClick"
  >
    <span class="investigate-icon pi pi-sparkles" aria-hidden="true" />
    <span class="investigate-label">{{ chip.label || chip.proposedQuestion }}</span>
  </Chip>
</template>

<style scoped>
.investigate-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  cursor: pointer;
  padding: 0.3rem 0.65rem;
  font-size: 0.75rem;
  background-color: var(--p-violet-50, #f5f3ff);
  border: 1px solid var(--p-violet-200, #ddd6fe);
  color: var(--p-violet-700, #6d28d9);
  transition: background-color 150ms ease, border-color 150ms ease;
}
.investigate-chip:hover,
.investigate-chip:focus-visible {
  background-color: var(--p-violet-100, #ede9fe);
  border-color: var(--p-violet-400, #a78bfa);
  outline: none;
}
.investigate-icon {
  font-size: 0.8rem;
}
</style>
