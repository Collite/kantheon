<script setup lang="ts">
import { ref } from 'vue'
import Card from 'primevue/card'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Message from 'primevue/message'
import { config } from "@/config";


defineProps<{
  connected: boolean
  loading: boolean
  error?: string
}>()

const emit = defineEmits<{
  (e: 'connect', url: string): void
  (e: 'disconnect'): void
}>()

const erpUrl = config.erpMcp.baseUrl || 'http://localhost:7153/mcp'
const fuzzyUrl = config.fuzzyMcp.baseUrl || 'http://localhost:7152/mcp'
const metaUrl = config.metaMcp.baseUrl || 'http://localhost:7154/mcp'
const localMetaUrl = config.localMetaMcp.baseUrl || 'http://localhost:7199/mcp'

const url = ref(erpUrl)

const presetOptions = [
  { label: 'ERP Data', value: erpUrl },
  { label: 'Metadata', value: metaUrl },
  { label: 'Fuzzy Matcher', value: fuzzyUrl },
  { label: 'Local Metadata', value: localMetaUrl },
]

const connect = () => {
  emit('connect', url.value)
}

const disconnect = () => {
  emit('disconnect')
}
</script>

<template>
  <Card class="mb-6">
    <template #content>
      <div class="flex flex-col gap-4">
        <div class="flex items-end gap-3 flex-wrap">
          <div class="flex-1 min-w-[260px]">
            <label for="mcp-url" class="block text-sm font-medium mb-1"
                   style="color: var(--p-surface-700);">
              MCP Server URL
            </label>
            <InputText
              id="mcp-url"
              v-model="url"
              :disabled="connected || loading"
              :placeholder="erpUrl"
              class="w-full"
            />
          </div>
          <div class="min-w-[180px]">
            <label for="mcp-preset" class="block text-sm font-medium mb-1"
                   style="color: var(--p-surface-700);">Preset</label>
            <Select
              id="mcp-preset"
              v-model="url"
              :options="presetOptions"
              option-label="label"
              option-value="value"
              :disabled="connected || loading"
              class="w-full"
            />
          </div>
          <div>
            <Button
              v-if="!connected"
              severity="success"
              icon="pi pi-link"
              label="Connect"
              :loading="loading"
              :disabled="loading"
              @click="connect"
            />
            <Button
              v-else
              severity="danger"
              icon="pi pi-times-circle"
              label="Disconnect"
              :loading="loading"
              :disabled="loading"
              @click="disconnect"
            />
          </div>
        </div>

        <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

        <div v-if="connected" class="flex items-center text-sm"
             style="color: var(--p-green-600);">
          <span class="w-2 h-2 rounded-full mr-2" style="background-color: var(--p-green-500);"></span>
          Connected to {{ url }}
        </div>
      </div>
    </template>
  </Card>
</template>
