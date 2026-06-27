<script setup lang="ts">
import AgentGraphView from '../chat/AgentGraphView.vue'
import { useAgentSession } from '@/composables/useAgentSession'

const session = useAgentSession()
</script>

<template>
  <div class="graph-panel relative h-full w-full">
    <div
      v-if="session.agentStatus.value"
      class="absolute top-3 right-3 z-10 px-3 py-1 rounded-full text-xs font-medium border shadow-sm"
      style="background-color: var(--p-primary-100); color: var(--p-primary-800); border-color: var(--p-primary-200);"
    >
      {{ session.agentStatus.value }}
    </div>
    <AgentGraphView
      :current-node="session.currentNode.value"
      :current-tool="session.currentTool.value"
      :traversed-nodes="session.traversedNodes.value"
    />
  </div>
</template>

<style scoped>
.graph-panel {
  background-color: var(--p-surface-50);
}
</style>
