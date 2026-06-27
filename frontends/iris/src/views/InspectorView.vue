<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import Panel from 'primevue/panel'
import ConnectionPanel from '../components/inspector/ConnectionPanel.vue'
import ToolList from '../components/inspector/ToolList.vue'
import ToolExecutor from '../components/inspector/ToolExecutor.vue'
import { mcpClient } from '../services/mcpClient'
import type { Tool } from '@modelcontextprotocol/sdk/types.js'

const connected = ref(false)
const connectionLoading = ref(false)
const connectionError = ref('')

const tools = ref<Tool[]>([])
const toolsLoading = ref(false)

const selectedTool = ref<Tool | null>(null)
const executionLoading = ref(false)
const executionResult = ref<any>(null)

const handleConnect = async (url: string) => {
  if (connected.value) return
  connectionLoading.value = true
  connectionError.value = ''
  try {
    await mcpClient.connect(url)
    connected.value = true
    await loadTools()
  } catch (e: any) {
    connectionError.value = "Failed to connect: " + (e.message || String(e))
    connected.value = false
  } finally {
    connectionLoading.value = false
  }
}

const handleDisconnect = async () => {
  await mcpClient.disconnect()
  connected.value = false
  tools.value = []
  selectedTool.value = null
  executionResult.value = null
}

const loadTools = async () => {
  toolsLoading.value = true
  try {
    const result = await mcpClient.listTools()
    tools.value = result.tools
  } catch (e: any) {
    connectionError.value = "Failed to list tools: " + e.message
  } finally {
    toolsLoading.value = false
  }
}

const handleSelectTool = (tool: Tool) => {
  selectedTool.value = tool
  executionResult.value = null
}

const handleExecute = async (name: string, args: any) => {
  executionLoading.value = true
  executionResult.value = null
  try {
    const result = await mcpClient.callTool(name, args)
    executionResult.value = result
  } catch (e: any) {
    executionResult.value = { error: e.message }
  } finally {
    executionLoading.value = false
  }
}

onUnmounted(() => {
  if (connected.value) {
      mcpClient.disconnect()
  }
})
</script>

<template>
  <div class="p-2 h-[calc(100vh-3rem)] flex flex-col overflow-hidden">
    <h1 class="text-2xl font-bold mb-4" style="color: var(--p-surface-900);">MCP Inspector</h1>

    <ConnectionPanel
      :connected="connected"
      :loading="connectionLoading"
      :error="connectionError"
      @connect="handleConnect"
      @disconnect="handleDisconnect"
    />

    <div v-if="connected" class="flex-1 flex flex-col gap-4 min-h-0">
      <div class="flex-1 flex gap-4 min-h-0">
        <div class="w-1/3 min-w-[300px] flex flex-col">
          <ToolList
            :tools="tools"
            :loading="toolsLoading"
            @select-tool="handleSelectTool"
          />
        </div>

        <div class="flex-1 flex flex-col min-w-0">
           <ToolExecutor
            :tool="selectedTool"
            :loading="executionLoading"
            @execute="handleExecute"
            @close="selectedTool = null"
          />
        </div>
      </div>

      <Panel header="LogViewer (Result)" class="result-panel h-1/3 min-h-[200px]">
        <pre v-if="executionResult" class="result-pre">{{ JSON.stringify(executionResult, null, 2) }}</pre>
        <div v-else class="italic" style="color: var(--p-surface-400);">No execution result</div>
      </Panel>
    </div>
  </div>
</template>

<style scoped>
.result-panel :deep(.p-panel-content) {
  background-color: #0f172a;
  color: #f1f5f9;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.85rem;
  border-radius: 0 0 var(--p-panel-border-radius) var(--p-panel-border-radius);
  padding: 1rem;
  overflow: auto;
  min-height: 0;
  flex: 1;
}
.result-panel :deep(.p-panel) {
  display: flex;
  flex-direction: column;
}
.result-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
