<script setup lang="ts">
import { ref, watch } from 'vue'
import Panel from 'primevue/panel'
import Button from 'primevue/button'
import Textarea from 'primevue/textarea'
import Message from 'primevue/message'
import type { Tool } from '@modelcontextprotocol/sdk/types.js'

const props = defineProps<{
  tool: Tool | null
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'execute', name: string, args: any): void
  (e: 'close'): void
}>()

const argsJson = ref('{}')
const error = ref('')

watch(() => props.tool, (newTool) => {
  if (newTool) {
    argsJson.value = JSON.stringify(generateDefaultArgs(newTool.inputSchema), null, 2)
    error.value = ''
  } else {
    argsJson.value = '{}'
  }
})

const generateDefaultArgs = (schema: any) => {
    if (!schema || !schema.properties) return {}
    const defaults: any = {}
    for (const key in schema.properties) {
        if (schema.required && schema.required.includes(key)) {
            const type = schema.properties[key].type
            if (type === 'string') defaults[key] = ""
            else if (type === 'number') defaults[key] = 0
            else if (type === 'boolean') defaults[key] = false
            else if (type === 'array') defaults[key] = []
            else if (type === 'object') defaults[key] = {}
            else defaults[key] = null
        }
    }
    return defaults
}

const execute = () => {
  try {
    const args = JSON.parse(argsJson.value)
    if (props.tool) {
        emit('execute', props.tool.name, args)
    }
  } catch (e: any) {
    error.value = "Invalid JSON: " + e.message
  }
}
</script>

<template>
  <Panel
    v-if="tool"
    :header="`Execute: ${tool.name}`"
    class="h-full flex flex-col tool-executor-panel"
  >
    <div class="mb-2 text-sm" style="color: var(--p-surface-600);">{{ tool.description }}</div>

    <div class="flex-1 flex flex-col min-h-0">
      <label class="block text-sm font-medium mb-1" style="color: var(--p-surface-700);">
        Arguments (JSON)
      </label>
      <Textarea
        v-model="argsJson"
        autoResize
        class="flex-1 font-mono text-sm"
        :pt="{ root: { style: 'min-height: 12rem; resize: vertical;' } }"
      />
      <Message v-if="error" severity="error" :closable="false" class="mt-1">{{ error }}</Message>
    </div>

    <div class="mt-4 flex justify-end gap-2">
      <Button severity="secondary" label="Cancel" @click="emit('close')" />
      <Button
        icon="pi pi-play"
        label="Execute"
        :loading="loading"
        :disabled="loading"
        @click="execute"
      />
    </div>
  </Panel>
  <div
    v-else
    class="h-full flex items-center justify-center rounded-lg border-2 border-dashed"
    style="color: var(--p-surface-500); background-color: var(--p-surface-50); border-color: var(--p-surface-200);"
  >
    Select a tool to execute
  </div>
</template>

<style scoped>
.tool-executor-panel :deep(.p-panel-content) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
</style>
