<script setup lang="ts">
// Iris Phase 3 Stage 3.2 T5 — chip strip, discriminated on the envelope/v1
// `Chip` oneof (prompt | routing | investigate).
//
//   - prompt      → a suggested-query chip; click re-submits as a normal turn.
//   - routing     → a RoutingPickChip (needs_user_pick); click re-issues the
//                   original question pinned to the chosen agent.
//   - investigate → an InvestigateChip (PD-1); click escalates the turn to Pythia.
//
// The parent (ChatBubble) owns each dispatch — it holds the session, the turn
// id, and the originating question. This component is presentation + routing of
// the click to the right typed event.
import Chip from 'primevue/chip'
import { useI18n } from 'vue-i18n'
import RoutingPickChip from './RoutingPickChip.vue'
import InvestigateChip from './InvestigateChip.vue'
import type {
  Chip as EnvelopeChip,
  InvestigateChip as InvestigateChipT,
  PromptChip,
} from '@/types/envelope'

defineProps<{ chips: EnvelopeChip[] | undefined }>()

const emit = defineEmits<{
  (e: 'prompt', chip: PromptChip): void
  (e: 'pick', agentId: string, label: string): void
  (e: 'investigate', chip: InvestigateChipT): void
}>()

const { t } = useI18n()

// Stable key per chip — the oneof arm has no id, so index-suffix the arm name.
const chipKey = (chip: EnvelopeChip, i: number): string =>
  chip.routing ? `r-${i}` : chip.investigate ? `i-${i}` : `p-${i}`
</script>

<template>
  <div v-if="chips && chips.length > 0" class="chip-strip" :aria-label="t('chat.chipStrip.ariaLabel')">
    <template v-for="(chip, i) in chips" :key="chipKey(chip, i)">
      <RoutingPickChip
        v-if="chip.routing"
        :chip="chip.routing"
        @pick="(agentId, label) => emit('pick', agentId, label)"
      />
      <InvestigateChip
        v-else-if="chip.investigate"
        :chip="chip.investigate"
        @investigate="(c) => emit('investigate', c)"
      />
      <Chip
        v-else-if="chip.prompt"
        class="prompt-chip cursor-pointer"
        :label="chip.prompt.display"
        :aria-label="t('chat.chipStrip.chipAriaLabel', { label: chip.prompt.display })"
        tabindex="0"
        @click="emit('prompt', chip.prompt)"
        @keydown.enter="emit('prompt', chip.prompt)"
        @keydown.space.prevent="emit('prompt', chip.prompt)"
      />
    </template>
  </div>
</template>

<style scoped>
.chip-strip {
  margin-top: 0.5rem;
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  align-items: center;
}
.prompt-chip {
  font-size: 0.75rem;
  background-color: var(--p-surface-50);
  color: var(--p-surface-700);
  border: 1px solid var(--p-surface-200);
  transition: background-color 150ms ease, color 150ms ease, border-color 150ms ease;
}
.prompt-chip:hover {
  background-color: var(--p-primary-50);
  border-color: var(--p-primary-300);
  color: var(--p-primary-700);
}
</style>
