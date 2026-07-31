<script setup lang="ts">
// Custom dockview tab header for promoted panels: title + close button.
//
// dockview-vue calls a tab component with the same prop shape as a panel
// component: `{ params: <user>, api, containerApi, tabLocation }`. The
// `api.close()` action removes the panel (which fires `onDidRemovePanel`,
// our cue to clean up tabsStore).
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { useTabsStore } from '@/stores/tabsStore'
import { downloadMarkdown, slugify } from './downloadMarkdown'

interface DockviewTabParams {
  params?: { panelId?: string }
  api?: { close: () => void; setTitle: (title: string) => void }
}

const props = defineProps<{
  params: DockviewTabParams
}>()

const tabsStore = useTabsStore()
const { t } = useI18n()

const panelId = computed(() => props.params?.params?.panelId)
const panel = computed(() => (panelId.value ? tabsStore.panels[panelId.value] : undefined))
const title = computed(() => panel.value?.title ?? 'Untitled')

const toast = useToast()

/**
 * A markdown panel can be saved. Gated on the format rather than on "is this a
 * protocol", because any markdown panel is equally savable and a type check
 * here would be a lie about why the button exists.
 */
const markdown = computed(() => {
  const env = panel.value?.format as { text?: string; format?: { kind?: unknown } } | undefined
  const kind = env?.format?.kind
  const isMarkdown = kind === 'MARKDOWN' || kind === 3
  return isMarkdown && env?.text ? env.text : undefined
})

const onSave = (event: MouseEvent) => {
  event.stopPropagation()
  const text = markdown.value
  if (!text) return
  // Byte-for-byte what the server rendered — never re-serialised through the
  // markdown viewer, which would silently normalise the document.
  // The panel names itself when it knows the contracted name (`/protocol`
  // does); otherwise fall back to a slug of the title.
  const name = panel.value?.downloadName ?? `${slugify(title.value)}.md`
  downloadMarkdown(text, name)
  toast.add({ severity: 'success', summary: t('slash.protocolDownloaded'), life: 1500 })
}

const onClose = (event: MouseEvent) => {
  event.stopPropagation()
  props.params?.api?.close()
}
</script>

<template>
  <div class="tab-header">
    <span class="tab-title" :title="title">{{ title }}</span>
    <Button
      v-if="markdown"
      text
      rounded
      size="small"
      icon="pi pi-download"
      class="tab-close"
      :aria-label="t('tabs.saveTabAria', { title })"
      @click.stop="onSave"
      @mousedown.stop
    />
    <Button
      text
      rounded
      size="small"
      icon="pi pi-times"
      class="tab-close"
      :aria-label="t('tabs.closeTabAria', { title })"
      @click.stop="onClose"
      @mousedown.stop
    />
  </div>
</template>

<style scoped>
.tab-header {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0 0.65rem 0 0.85rem;
  height: 100%;
  font-size: 0.78rem;
  color: var(--p-surface-700);
  max-width: 18rem;
  min-width: 6rem;
}
.tab-title {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tab-close :deep(.p-button) {
  width: 1.25rem !important;
  height: 1.25rem !important;
  padding: 0 !important;
}
.tab-close :deep(.p-button-icon) {
  font-size: 0.7rem;
}
</style>
