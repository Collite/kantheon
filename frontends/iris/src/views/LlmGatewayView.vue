<script setup lang="ts">
import { ref } from 'vue'
import Card from 'primevue/card'
import Panel from 'primevue/panel'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Textarea from 'primevue/textarea'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'
import ScrollPanel from 'primevue/scrollpanel'
import Message from 'primevue/message'
import {
  type ChatCompletionRequestApi,
  type ChatResponse,
  llmGatewayService,
  type ReasoningConfig
} from '../services/llmGatewayService'

type Mode = 'simple' | 'advanced'

const mode = ref<Mode>('simple')
const modeOptions = [
  { label: 'Simple', value: 'simple' },
  { label: 'Advanced', value: 'advanced' },
]

const isLoading = ref(false)
const error = ref<string | null>(null)
const response = ref<ChatResponse | null>(null)

const modelTag = ref('llama3-70b-8192')
const userQuery = ref('')

const systemQuery = ref('')
const conversationId = ref('')
const model = ref('')
const temperature = ref<number | undefined>(undefined)
const maxOutputTokens = ref<number | undefined>(undefined)
const maxToolsCalls = ref<number | undefined>(undefined)
const reasoningEffort = ref<string>('REASONING_EFFORT_UNSPECIFIED')
const instructions = ref('')
const background = ref('')

const reasoningOptions = [
  { label: 'Unspecified', value: 'REASONING_EFFORT_UNSPECIFIED' },
  { label: 'Low', value: 'LOW' },
  { label: 'Medium', value: 'MEDIUM' },
  { label: 'High', value: 'HIGH' },
]

const sendRequest = async () => {
  isLoading.value = true
  error.value = null
  response.value = null

  try {
    const request: ChatCompletionRequestApi = {
      modelTags: [modelTag.value],
      messages: [
        { role: 'user', content: userQuery.value }
      ]
    }

    if (mode.value === 'advanced') {
      if (systemQuery.value) {
        request.messages?.unshift({ role: 'system', content: systemQuery.value })
      }

      if (model.value) request.model = model.value
      if (conversationId.value) request.conversation = conversationId.value
      if (temperature.value !== undefined) request.temperature = temperature.value
      if (maxOutputTokens.value !== undefined) request.maxOutputTokens = maxOutputTokens.value
      if (maxToolsCalls.value !== undefined) request.maxToolsCalls = maxToolsCalls.value

      if (reasoningEffort.value !== 'REASONING_EFFORT_UNSPECIFIED') {
        request.reasoning = { effort: reasoningEffort.value as ReasoningConfig['effort'] }
      }

      if (instructions.value) request.instructions = instructions.value
      if (background.value) request.background = background.value
    }

    const res = await llmGatewayService.createResponse(request)
    response.value = res
  } catch (err: any) {
    console.error('Error sending request:', err)
    error.value = err.message || 'An error occurred while communicating with LLM Gateway.'
  } finally {
    isLoading.value = false
  }
}
</script>

<template>
  <div class="max-w-4xl mx-auto space-y-6">
    <div class="sm:flex sm:items-center sm:justify-between">
      <h1 class="text-2xl font-bold leading-7 sm:truncate sm:text-3xl sm:tracking-tight"
          style="color: var(--p-surface-900);">
        LLM Gateway
      </h1>
      <SelectButton
        v-model="mode"
        :options="modeOptions"
        option-label="label"
        option-value="value"
        :allow-empty="false"
        aria-label="Select form mode"
      />
    </div>

    <Card>
      <template #content>
        <div class="space-y-6">
          <div class="grid grid-cols-1 gap-x-6 gap-y-6 sm:grid-cols-6">
            <div class="sm:col-span-3 flex flex-col">
              <label for="model-tag" class="block text-sm font-medium mb-1"
                     style="color: var(--p-surface-900);">Model Tag</label>
              <InputText id="model-tag" v-model="modelTag" placeholder="e.g. gpt-4o" />
            </div>

            <div class="col-span-full flex flex-col">
              <label for="user-query" class="block text-sm font-medium mb-1"
                     style="color: var(--p-surface-900);">User Query</label>
              <Textarea
                id="user-query"
                v-model="userQuery"
                rows="4"
                placeholder="What can you help me with today?"
                autoResize
              />
            </div>
          </div>

          <div v-if="mode === 'advanced'"
               class="grid grid-cols-1 gap-x-6 gap-y-6 sm:grid-cols-6 pt-4 border-t"
               style="border-color: var(--p-surface-200);">

            <div class="col-span-full flex flex-col">
              <label for="system-query" class="block text-sm font-medium mb-1"
                     style="color: var(--p-surface-900);">System Query</label>
              <Textarea
                id="system-query"
                v-model="systemQuery"
                rows="2"
                placeholder="You are a helpful assistant."
                autoResize
              />
            </div>

            <div class="sm:col-span-3 flex flex-col">
              <label for="conversation-id" class="block text-sm font-medium mb-1"
                     style="color: var(--p-surface-900);">Conversation ID</label>
              <InputText id="conversation-id" v-model="conversationId" />
            </div>

            <div class="sm:col-span-3 flex flex-col">
              <label for="model" class="block text-sm font-medium mb-1"
                     style="color: var(--p-surface-900);">Specific Model</label>
              <InputText id="model" v-model="model" />
            </div>

            <div class="sm:col-span-2 flex flex-col">
              <label for="temperature" class="block text-sm font-medium mb-1"
                     style="color: var(--p-surface-900);">Temperature</label>
              <InputNumber
                id="temperature"
                v-model="temperature"
                :min-fraction-digits="0"
                :max-fraction-digits="2"
                :min="0"
                :max="2"
                :step="0.1"
                show-buttons
                button-layout="horizontal"
                :input-style="{ width: '5rem', textAlign: 'center' }"
              />
            </div>

            <div class="sm:col-span-2 flex flex-col">
              <label for="max-tokens" class="block text-sm font-medium mb-1"
                     style="color: var(--p-surface-900);">Max Output Tokens</label>
              <InputNumber id="max-tokens" v-model="maxOutputTokens" :min="0" />
            </div>

            <div class="sm:col-span-2 flex flex-col">
              <label for="max-tools" class="block text-sm font-medium mb-1"
                     style="color: var(--p-surface-900);">Max Tool Calls</label>
              <InputNumber id="max-tools" v-model="maxToolsCalls" :min="0" />
            </div>

            <div class="sm:col-span-3 flex flex-col">
              <label for="reasoning-effort" class="block text-sm font-medium mb-1"
                     style="color: var(--p-surface-900);">Reasoning Effort</label>
              <Select
                id="reasoning-effort"
                v-model="reasoningEffort"
                :options="reasoningOptions"
                option-label="label"
                option-value="value"
              />
            </div>

            <div class="col-span-full flex flex-col">
              <label for="instructions" class="block text-sm font-medium mb-1"
                     style="color: var(--p-surface-900);">Instructions</label>
              <Textarea id="instructions" v-model="instructions" rows="2" autoResize />
            </div>

            <div class="col-span-full flex flex-col">
              <label for="background" class="block text-sm font-medium mb-1"
                     style="color: var(--p-surface-900);">Background</label>
              <Textarea id="background" v-model="background" rows="2" autoResize />
            </div>
          </div>

          <div class="flex justify-end pt-4 border-t"
               style="border-color: var(--p-surface-200);">
            <Button
              :label="isLoading ? 'Sending...' : 'Send Request'"
              :icon="isLoading ? 'pi pi-spin pi-spinner' : 'pi pi-play'"
              :loading="isLoading"
              :disabled="isLoading || !userQuery"
              @click="sendRequest"
            />
          </div>
        </div>
      </template>
    </Card>

    <Panel v-if="response || error" header="Response" class="response-panel">
      <Message v-if="error" severity="error" :closable="false">
        <template #default>
          <div class="font-medium">Request Failed</div>
          <div class="mt-1 text-sm">{{ error }}</div>
        </template>
      </Message>

      <ScrollPanel v-else-if="response" style="width: 100%; height: 480px;">
        <pre class="response-pre">{{ JSON.stringify(response, null, 2) }}</pre>
      </ScrollPanel>
    </Panel>
  </div>
</template>

<style scoped>
.response-pre {
  background-color: var(--p-surface-50);
  color: var(--p-surface-900);
  padding: 1rem;
  border-radius: 0.5rem;
  font-size: 0.78rem;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
}
</style>
