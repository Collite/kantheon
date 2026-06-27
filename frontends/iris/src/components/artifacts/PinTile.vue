<script setup lang="ts">
// Iris Phase 4 Stage 4.2 — a pinned view tile (PD-6). Renders the pin's captured
// envelope through the shared format catalog and a footer with refreshed-at, a
// PD-9 provenance ⓘ, a PD-4 scope indicator, and refresh/delete actions. A failed
// refresh surfaces an explicit stale/error banner (never silently wrong).
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { resolveRenderer } from '@/catalog/formatCatalog'
import { FormatEnvelope, FormatKind, envelopeContent } from '@/types/envelope'
import type { ArtifactDto } from '@/types/artifacts'

// `loading` is owned by the parent (ArtifactsPanel): the actual refresh is async
// there, so the spinner has to reflect the parent's per-pin in-flight state — a
// local flag would flip back synchronously before the request ever resolved.
const props = defineProps<{ pin: ArtifactDto; loading?: boolean }>()
const emit = defineEmits<{
  (e: 'refresh', id: string): void
  (e: 'remove', id: string): void
}>()

const { t } = useI18n()

const envelope = computed<FormatEnvelope | null>(() => {
  if (!props.pin.envelope) return null
  try {
    return FormatEnvelope.fromJSON(props.pin.envelope)
  } catch {
    return null
  }
})

const kind = computed<FormatKind>(() => envelope.value?.format?.kind ?? FormatKind.PLAINTEXT)
const renderer = computed(() => resolveRenderer(kind.value))
const details = computed(() => {
  const fmt = envelope.value?.format
  if (!fmt) return undefined
  return kind.value === FormatKind.TABLE
    ? fmt.table
    : kind.value === FormatKind.CHART
      ? fmt.chart
      : kind.value === FormatKind.MARKDOWN
        ? fmt.markdown
        : undefined
})

const provenance = computed(() => props.pin.provenance as { patternId?: string; sql?: string } | undefined)

// PD-4 scope indicator: the entity bindings captured at pin time.
const scope = computed(() => {
  const ctx = props.pin.appliedContext
  if (!Array.isArray(ctx)) return ''
  return (ctx as Array<{ display?: string }>)
    .map((b) => b.display)
    .filter(Boolean)
    .join(', ')
})

const refreshedAtLabel = computed(() =>
  props.pin.refreshedAt ? new Date(props.pin.refreshedAt).toLocaleString() : t('artifacts.neverRefreshed'),
)

const onRefresh = () => {
  emit('refresh', props.pin.artifactId)
}
</script>

<template>
  <div class="pin-tile" :class="{ 'pin-tile--error': !!pin.refreshError }">
    <header class="pin-tile-head">
      <span class="pin-tile-name" :title="pin.name">{{ pin.name }}</span>
      <span v-if="pin.agentId" class="pin-tile-agent">{{ pin.agentId }}</span>
    </header>

    <div v-if="pin.refreshError" class="pin-tile-stale" role="alert">
      {{ t('artifacts.staleError', { error: pin.refreshError }) }}
    </div>

    <div class="pin-tile-body">
      <component
        :is="renderer"
        v-if="envelope"
        :text="envelope.text"
        :content="envelopeContent(envelope)"
        :details="details"
        :kind="kind"
      />
      <p v-else class="pin-tile-empty">{{ t('artifacts.noEnvelope') }}</p>
    </div>

    <footer class="pin-tile-foot">
      <span class="pin-tile-meta">
        {{ t('artifacts.refreshedAt', { at: refreshedAtLabel }) }}
        <span
          v-if="provenance?.patternId"
          class="pin-tile-prov pi pi-info-circle"
          :title="provenance.sql || provenance.patternId"
          aria-hidden="true"
        />
        <span v-if="scope" class="pin-tile-scope" :title="scope">⦿ {{ scope }}</span>
      </span>
      <span class="pin-tile-actions">
        <Button
          text
          size="small"
          icon="pi pi-refresh"
          :loading="!!loading"
          :aria-label="t('artifacts.refresh')"
          v-tooltip.bottom="t('artifacts.refresh')"
          @click="onRefresh"
        />
        <Button
          text
          size="small"
          severity="danger"
          icon="pi pi-trash"
          :aria-label="t('artifacts.remove')"
          v-tooltip.bottom="t('artifacts.remove')"
          @click="emit('remove', pin.artifactId)"
        />
      </span>
    </footer>
  </div>
</template>

<style scoped>
.pin-tile {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  padding: 0.6rem 0.75rem;
  border: 1px solid var(--p-surface-200);
  border-radius: 0.75rem;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.pin-tile--error {
  border-color: var(--p-red-300, #fca5a5);
}
.pin-tile-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem;
}
.pin-tile-name {
  font-weight: 600;
  font-size: 0.85rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pin-tile-agent {
  font-size: 0.68rem;
  color: var(--p-surface-500);
}
.pin-tile-stale {
  font-size: 0.72rem;
  color: var(--p-red-700, #b91c1c);
  background: var(--p-red-50, #fef2f2);
  border-radius: 0.4rem;
  padding: 0.25rem 0.4rem;
}
.pin-tile-body {
  font-size: 0.8rem;
  overflow: auto;
  max-height: 16rem;
}
.pin-tile-empty {
  color: var(--p-surface-500);
  font-style: italic;
}
.pin-tile-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}
.pin-tile-meta {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.68rem;
  color: var(--p-surface-500);
}
.pin-tile-prov {
  cursor: help;
}
.pin-tile-scope {
  color: var(--p-primary-600);
}
</style>
