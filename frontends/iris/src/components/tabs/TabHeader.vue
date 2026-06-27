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
import { useTabsStore } from '@/stores/tabsStore'

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

const onClose = (event: MouseEvent) => {
  event.stopPropagation()
  props.params?.api?.close()
}
</script>

<template>
  <div class="tab-header">
    <span class="tab-title" :title="title">{{ title }}</span>
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
