<script setup lang="ts">
// Phase 2 — Agent Flow pane.
//
// Read-only textual transcript of LangGraph events for the current
// session. Entries accumulate across chat turns — useful for debugging
// the LLM-driven flow without tailing the BE log. The store
// (`agentFlowStore`) is populated by `agentService.ts` from the same SSE
// stream that drives Chat + Graph, so no new BE work was needed.
//
// UX:
//   - Sticky-to-bottom auto-scroll (the same `userPinnedUp` pattern as
//     ChatPanel — when the user scrolls up, new entries don't drag them
//     back down; a "scroll to latest" affordance sits at the bottom).
//   - Toolbar: Clear, Verbose toggle, Copy all.
//   - Verbose off: one line per entry. Verbose on: a second indented
//     line carrying JSON-stringified args/details (truncated ~512 chars).
//   - Verbose state persists per user (localStorage key
//     `golem.pane.flow.v1.<userId>`).
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import ToggleButton from 'primevue/togglebutton'
import { useToast } from 'primevue/usetoast'
import { useAuthStore } from '@/stores/auth'
import { useAgentFlowStore, type FlowEntry } from '@/stores/agentFlowStore'

const flowStore = useAgentFlowStore()
const authStore = useAuthStore()
const toast = useToast()
const { t } = useI18n()

// ── Verbose toggle (persisted) ─────────────────────────────────────────
const verboseStorageKey = computed(
  () => `golem.pane.flow.v1.${authStore.userId ?? 'anon'}`,
)
const verbose = ref(false)

const readVerbose = (): boolean => {
  try {
    const raw = localStorage.getItem(verboseStorageKey.value)
    if (raw == null) return false
    const parsed = JSON.parse(raw)
    return parsed?.verbose === true
  } catch {
    return false
  }
}
const writeVerbose = () => {
  try {
    localStorage.setItem(
      verboseStorageKey.value,
      JSON.stringify({ verbose: verbose.value }),
    )
  } catch {
    /* ignore quota errors */
  }
}
verbose.value = readVerbose()
watch(verbose, writeVerbose)

// ── Rendering helpers ──────────────────────────────────────────────────
const VERBOSE_TRUNCATE_CHARS = 512

const formatEntryHeader = (entry: FlowEntry): string => {
  switch (entry.kind) {
    case 'step':
      return `[${entry.ts}] step: ${entry.node}`
    case 'tool':
      return `[${entry.ts}] tool: ${entry.toolName} @ ${entry.server}`
    case 'envelope':
      return `[${entry.ts}] envelope: kind=${entry.envelopeKind} bubble_id=${entry.bubbleId}`
  }
}

const formatVerbosePayload = (entry: FlowEntry): string | null => {
  let payload: unknown
  if (entry.kind === 'tool') payload = entry.args
  else if (entry.kind === 'envelope') payload = entry.details
  else return null
  if (payload === undefined) return null
  let text: string
  try {
    text = JSON.stringify(payload)
  } catch {
    text = String(payload)
  }
  return text.length > VERBOSE_TRUNCATE_CHARS
    ? `${text.slice(0, VERBOSE_TRUNCATE_CHARS)}…`
    : text
}

const renderedTranscript = computed(() =>
  flowStore.entries
    .flatMap((entry) => {
      const lines: string[] = [formatEntryHeader(entry)]
      if (verbose.value) {
        const payload = formatVerbosePayload(entry)
        if (payload) lines.push(`    ${payload}`)
      }
      return lines
    })
    .join('\n'),
)

// ── Sticky-to-bottom auto-scroll ───────────────────────────────────────
const STICK_THRESHOLD_PX = 24
const scrollContainer = ref<HTMLElement | null>(null)
const userPinnedUp = ref(false)
let suppressNextScrollEvent = false

const isAtBottom = () => {
  const el = scrollContainer.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_THRESHOLD_PX
}

const scrollToBottom = async (smooth = false) => {
  await nextTick()
  const el = scrollContainer.value
  if (!el) return
  suppressNextScrollEvent = true
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  requestAnimationFrame(() => {
    suppressNextScrollEvent = false
  })
}

const onScroll = () => {
  if (suppressNextScrollEvent) return
  userPinnedUp.value = !isAtBottom()
}

const onScrollToLatest = () => {
  userPinnedUp.value = false
  void scrollToBottom(true)
}

watch(
  () => flowStore.entries.length,
  () => {
    if (!userPinnedUp.value) void scrollToBottom()
  },
)
// Re-render also when verbose changes the line count.
watch(verbose, () => {
  if (!userPinnedUp.value) void scrollToBottom()
})

onMounted(() => {
  void scrollToBottom()
  scrollContainer.value?.addEventListener('scroll', onScroll, { passive: true })
})
onBeforeUnmount(() => {
  scrollContainer.value?.removeEventListener('scroll', onScroll)
})

// ── Toolbar actions ────────────────────────────────────────────────────
const onClear = () => {
  flowStore.clear()
  userPinnedUp.value = false
  toast.add({
    severity: 'info',
    summary: t('panes.flow.cleared'),
    life: 2000,
  })
}

const onCopyAll = async () => {
  const text = renderedTranscript.value
  try {
    await navigator.clipboard.writeText(text)
    toast.add({
      severity: 'success',
      summary: t('panes.flow.copied'),
      detail: t('panes.flow.copiedDetail', { count: flowStore.entries.length }),
      life: 2000,
    })
  } catch {
    toast.add({
      severity: 'error',
      summary: t('panes.flow.copyFailed'),
      detail: t('panes.flow.copyFailedDetail'),
      life: 3000,
    })
  }
}

const isEmpty = computed(() => flowStore.entries.length === 0)
</script>

<template>
  <div class="flow-pane">
    <div class="flow-toolbar">
      <Button
        text
        plain
        size="small"
        icon="pi pi-trash"
        :label="t('panes.flow.clear')"
        :disabled="isEmpty"
        :aria-label="t('panes.flow.clearAria')"
        @click="onClear"
      />
      <ToggleButton
        v-model="verbose"
        :on-label="t('panes.flow.verbose')"
        :off-label="t('panes.flow.verbose')"
        on-icon="pi pi-eye"
        off-icon="pi pi-eye-slash"
        size="small"
        :aria-label="t('panes.flow.verboseAria')"
      />
      <Button
        text
        plain
        size="small"
        icon="pi pi-copy"
        :label="t('panes.flow.copyAll')"
        :disabled="isEmpty"
        :aria-label="t('panes.flow.copyAllAria')"
        @click="onCopyAll"
      />
      <span class="flow-count" aria-live="polite">
        {{ flowStore.entries.length }} / {{ flowStore.maxEntries }}
        <template v-if="flowStore.droppedCount > 0">
          ({{ flowStore.droppedCount }} dropped)
        </template>
      </span>
    </div>

    <div
      ref="scrollContainer"
      class="flow-scroll"
      role="log"
      aria-live="polite"
      :aria-label="t('panes.flow.logRegionAria')"
    >
      <pre v-if="!isEmpty" class="flow-pre">{{ renderedTranscript }}</pre>
      <div v-else class="flow-empty">
        {{ t('panes.flow.empty') }}
      </div>
    </div>

    <Button
      v-if="userPinnedUp && !isEmpty"
      class="flow-scroll-latest"
      rounded
      icon="pi pi-arrow-down"
      :aria-label="t('panes.flow.scrollLatestAria')"
      @click="onScrollToLatest"
    />
  </div>
</template>

<style scoped>
.flow-pane {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  background-color: var(--p-surface-50);
}

.flow-toolbar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.375rem 0.625rem;
  border-bottom: 1px solid var(--p-surface-200);
  background-color: #fff;
  flex-shrink: 0;
}

.flow-count {
  margin-left: auto;
  font-size: 0.7rem;
  color: var(--p-surface-500);
  font-variant-numeric: tabular-nums;
}

.flow-scroll {
  flex: 1;
  overflow-y: auto;
  overflow-x: auto;
  padding: 0.5rem 0.75rem;
}

.flow-pre {
  margin: 0;
  font-family:
    ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono',
    'Courier New', monospace;
  font-size: 0.78rem;
  line-height: 1.45;
  white-space: pre;
  color: var(--p-surface-800);
}

.flow-empty {
  padding: 1.25rem 0.5rem;
  font-size: 0.8rem;
  color: var(--p-surface-500);
  font-style: italic;
}

.flow-scroll-latest {
  position: absolute;
  right: 0.75rem;
  bottom: 0.75rem;
  z-index: 1;
  width: 2rem !important;
  height: 2rem !important;
  padding: 0 !important;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.15);
}
</style>
