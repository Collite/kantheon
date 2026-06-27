<script setup lang="ts">
import { ref, computed } from 'vue'
import Panel from 'primevue/panel'
import IconField from 'primevue/iconfield'
import InputIcon from 'primevue/inputicon'
import InputText from 'primevue/inputtext'
import ProgressSpinner from 'primevue/progressspinner'
import type { Tool } from '@modelcontextprotocol/sdk/types.js'

const props = defineProps<{
  tools: Tool[]
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'select-tool', tool: Tool): void
}>()

const searchQuery = ref('')

const filteredTools = computed(() => {
  if (!searchQuery.value) return props.tools
  const q = searchQuery.value.toLowerCase()
  return props.tools.filter(t =>
    t.name.toLowerCase().includes(q) ||
    t.description?.toLowerCase().includes(q)
  )
})
</script>

<template>
  <Panel header="Tools" class="h-full flex flex-col tool-list-panel">
    <div class="mb-4">
      <IconField>
        <InputIcon class="pi pi-search" />
        <InputText
          v-model="searchQuery"
          placeholder="Filter tools..."
          class="w-full"
          aria-label="Filter tools"
        />
      </IconField>
    </div>

    <div v-if="loading" class="flex items-center justify-center p-4 gap-2"
         style="color: var(--p-surface-500);">
      <ProgressSpinner style="width:1.25rem; height:1.25rem;" stroke-width="6" />
      <span>Loading tools...</span>
    </div>

    <div v-else-if="tools.length === 0" class="text-center p-4"
         style="color: var(--p-surface-500);">
      No tools available.
    </div>

    <div v-else class="flex-1 overflow-y-auto space-y-2 pr-1">
      <button
        v-for="tool in filteredTools"
        :key="tool.name"
        type="button"
        class="tool-row text-left w-full p-3 rounded-lg transition-colors"
        @click="emit('select-tool', tool)"
      >
        <div class="font-medium" style="color: var(--p-primary-700);">{{ tool.name }}</div>
        <div class="text-sm line-clamp-2" style="color: var(--p-surface-600);">
          {{ tool.description }}
        </div>
      </button>
    </div>
  </Panel>
</template>

<style scoped>
.tool-list-panel :deep(.p-panel-content) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.tool-row {
  background-color: #fff;
  border: 1px solid var(--p-surface-200);
}
.tool-row:hover {
  background-color: var(--p-surface-50);
  border-color: var(--p-primary-300);
}
.tool-row:focus-visible {
  outline: 2px solid var(--p-primary-500);
  outline-offset: 2px;
}
</style>
