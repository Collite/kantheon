<script setup lang="ts">
import { computed, watch } from 'vue'
import Select from 'primevue/select'
import DockviewWorkspace from '@/components/layout/DockviewWorkspace.vue'
import SessionRail from '@/components/layout/SessionRail.vue'
import ConnectionStatusDot from '@/components/layout/ConnectionStatusDot.vue'
import { useAgentSession, type Lang } from '@/composables/useAgentSession'
import { useAuthStore } from '@/stores/auth'
import { config } from '@/config'
import type { AgentKey } from '@/services/irisStream'

const props = defineProps<{
  agentId?: AgentKey
  customTitle?: Record<Lang, string>
}>()

const session = useAgentSession()
const authStore = useAuthStore()

/** The active agent id, defaulting to the first configured agent. */
const activeAgentId = computed<AgentKey>(
  () => props.agentId ?? config.golemAgents[0]?.id ?? 'golem',
)

const activeAgent = computed(() =>
  config.golemAgents.find((a) => a.id === activeAgentId.value) ?? config.golemAgents[0],
)

// (Re-)initialise the session whenever the selected agent changes — the route
// param drives this, and the same AgentView instance is reused across agent
// routes, so a watch (not onMounted) is what actually fires on a switch.
watch(
  activeAgentId,
  (id) => {
    void session.init(id, props.customTitle)
  },
  { immediate: true },
)

const t = computed(() => {
  const base = session.defaultTranslations[session.selectedLang.value]
  // Prefer an explicit per-language customTitle, then the agent's config label,
  // then the generic default title — so multiple agents are distinguishable.
  const override = session.customTitle.value?.[session.selectedLang.value] ?? activeAgent.value?.label
  return override ? { ...base, title: override } : base
})

const onLanguageChange = (lang: Lang) => {
  void session.changeLanguage(lang)
}

const agentBaseUrl = computed(() => {
  const baseUrl = activeAgent.value?.baseUrl || config.golem.baseUrl
  if (!baseUrl.startsWith('http')) {
    return `${window.location.protocol}//${baseUrl}`
  }
  return baseUrl
})
</script>

<template>
  <div class="agent-view h-[calc(100vh-3rem)] flex flex-col gap-2">
    <header class="flex items-center justify-between">
      <h1 class="text-base font-semibold m-0 leading-none" style="color: var(--p-surface-900);">
        {{ t.title }}
      </h1>

      <div class="flex items-center gap-3">
        <ConnectionStatusDot
          :base-url="agentBaseUrl"
          :user-id="authStore.userId"
        />
        <Select
          :model-value="session.selectedLang.value"
          :options="session.languages"
          option-label="label"
          option-value="code"
          size="small"
          class="lang-picker"
          aria-label="Select language"
          @update:model-value="onLanguageChange($event as Lang)"
        />
      </div>
    </header>

    <div class="flex flex-1 min-h-0 gap-2">
      <SessionRail class="rounded-xl border overflow-hidden" style="border-color: var(--p-surface-200);" />
      <div class="workspace-shell flex-1 min-w-0 min-h-0 overflow-hidden rounded-xl border"
           style="border-color: var(--p-surface-200);">
        <DockviewWorkspace />
      </div>
    </div>
  </div>
</template>

<style scoped>
.workspace-shell {
  background-color: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.lang-picker {
  width: 5.5rem;
  font-size: 0.75rem;
}
.lang-picker :deep(.p-select-label) {
  padding: 0.25rem 0.5rem;
  font-size: 0.75rem;
}
.lang-picker :deep(.p-select-dropdown) {
  width: 1.5rem;
}
</style>
