<script setup lang="ts">
// PromotedPanel — generic dockview panel hosting a renderer from
// `formatCatalog` based on the per-tab metadata in `tabsStore`.
//
// dockview-vue calls our component with a single prop named `params`
// whose shape is `{ params: <user-supplied>, api, containerApi, tabLocation }`.
// The user-supplied `params.panelId` is the key into `tabsStore.panels`.
//
// Phase 6: same display-changed plumbing as ChatBubble — the user can
// Show-as-Chart / Show-as-Table inside a tab too. The override lives on
// `tabsStore.panels[panelId].displayState.viewKind`.
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { resolveRenderer } from '@/catalog/formatCatalog'
import { useTabsStore } from '@/stores/tabsStore'
import { FormatKind, envelopeContent } from '@/types/envelope'

interface DockviewPanelParams {
  params?: { panelId?: string }
  api?: { close: () => void; setTitle: (t: string) => void }
}

const props = defineProps<{
  params: DockviewPanelParams
}>()

const tabsStore = useTabsStore()
const { panels } = storeToRefs(tabsStore)

const panelId = computed(() => props.params?.params?.panelId)
const panel = computed(() => (panelId.value ? panels.value[panelId.value] : undefined))

const displayState = computed(() => panel.value?.displayState)

const envelopeKind = computed<FormatKind | undefined>(
  () => panel.value?.format.format?.kind,
)

const effectiveKind = computed<FormatKind | undefined>(() => {
  const override = (displayState.value as { viewKind?: FormatKind } | undefined)?.viewKind
  return override ?? envelopeKind.value
})

const renderer = computed(() => resolveRenderer(effectiveKind.value))

const text = computed(() => panel.value?.format.text)
const content = computed(() => envelopeContent(panel.value?.format))
// When the override flips us to a different kind, the envelope's per-kind
// details describe the *original* shape — drop them so the override renderer
// falls back to its own synthesis (mirrors ChatBubble). envelope/v1 carries
// details on format.{table,chart,markdown} (no `details` union).
const details = computed(() => {
  if (envelopeKind.value && envelopeKind.value !== effectiveKind.value) return undefined
  const fmt = panel.value?.format.format
  return effectiveKind.value === FormatKind.TABLE
    ? fmt?.table
    : effectiveKind.value === FormatKind.CHART
      ? fmt?.chart
      : effectiveKind.value === FormatKind.MARKDOWN
        ? fmt?.markdown
        : undefined
})
const drilldowns = computed(() => panel.value?.format.drilldowns)

const onDisplayChanged = (state: { viewKind?: FormatKind }) => {
  if (!panelId.value) return
  tabsStore.updateDisplayState(panelId.value, state)
}
</script>

<template>
  <div class="promoted-panel h-full w-full overflow-auto">
    <component
      v-if="panel"
      :is="renderer"
      :text="text"
      :content="content"
      :details="details"
      :kind="effectiveKind"
      :drilldowns="drilldowns"
      :display-state="displayState"
      @display-changed="onDisplayChanged"
    />
    <div
      v-else
      class="missing-panel h-full flex items-center justify-center"
      style="color: var(--p-surface-500);"
    >
      Panel content unavailable.
    </div>
  </div>
</template>

<style scoped>
.promoted-panel {
  background-color: #fff;
  padding: 1.25rem;
}
</style>
