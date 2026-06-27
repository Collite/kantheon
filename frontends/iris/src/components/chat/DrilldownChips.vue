<script setup lang="ts">
// Stage 07-B B-4 — per-row drilldown chips with PREFILL behaviour (OQ-07.B).
//
// On click we prefill the chat input with a templated user_text built from
// the drilldown's `arg_mapping` against the row's column values, focus the
// input, and let the user review/edit before submitting. No typed-action
// crosses the wire — the next turn is a plain /v2/chat call carrying the
// prefilled text.
import { computed } from 'vue'
import Chip from 'primevue/chip'
import type { Drilldown } from '@/types/envelope'

interface RowLike {
  [column: string]: unknown
}

const props = defineProps<{
  drilldowns?: Drilldown[]
  row: RowLike
}>()

const emit = defineEmits<{
  (e: 'prefill', userText: string, drilldown: Drilldown): void
}>()

// Substitute the drilldown's arg_mapping against the row values. The mapping
// is `target_param -> source_expr`; source_expr is either a column name
// (looked up in the row) or a literal (quoted). Stage 07 keeps the lookup
// simple: bare identifiers → row[col]; quoted strings or numbers → literal.
function resolveArg(sourceExpr: string, row: RowLike): string {
  const trimmed = sourceExpr.trim()
  if (
    (trimmed.startsWith("'") && trimmed.endsWith("'"))
    || (trimmed.startsWith('"') && trimmed.endsWith('"'))
  ) {
    return trimmed.slice(1, -1)
  }
  if (!Number.isNaN(Number(trimmed))) {
    return trimmed
  }
  const raw = row[trimmed] ?? row[trimmed.toUpperCase()] ?? row[trimmed.toLowerCase()]
  return raw === undefined || raw === null ? '' : String(raw)
}

// "Display + space + key=value pairs" — terse fallback when we don't have a
// target-pattern example to template against. The chip's `display` already
// carries the intent (e.g. "Detail dokladu"), so appending the resolved
// values keeps the prefilled text natural enough for the user to submit
// without editing.
function templatedText(d: Drilldown, row: RowLike): string {
  const parts = Object.entries(d.argMapping).map(
    ([param, srcExpr]) => `${param}=${resolveArg(srcExpr, row)}`,
  )
  return parts.length > 0 ? `${d.display} (${parts.join(', ')})` : d.display
}

const onClick = (d: Drilldown) => {
  emit('prefill', templatedText(d, props.row), d)
}

const visibleDrilldowns = computed(() => (props.drilldowns ?? []).filter(d => d.scope === 'row'))
</script>

<template>
  <div v-if="visibleDrilldowns.length > 0" class="drilldown-chips">
    <Chip
      v-for="d in visibleDrilldowns"
      :key="d.id"
      :label="d.display"
      icon="pi pi-external-link"
      class="drilldown-chip"
      :class="`drilldown-chip--${d.source}`"
      tabindex="0"
      :title="`${d.targetPatternId} (${d.source})`"
      @click="onClick(d)"
      @keydown.enter="onClick(d)"
      @keydown.space.prevent="onClick(d)"
    />
  </div>
</template>

<style scoped>
.drilldown-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
  padding: 0.25rem 0;
}
.drilldown-chip {
  cursor: pointer;
  font-size: 0.75rem;
}
.drilldown-chip--explicit_ttr :deep(.p-chip-icon) {
  color: var(--p-primary-color);
}
</style>
