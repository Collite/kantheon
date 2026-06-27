<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'

interface NodeData {
  label?: string
  active?: boolean
  traversed?: boolean
}

const props = defineProps<{
  id: string
  label?: string
  data?: NodeData
}>()

const fullLabel = computed(() => props.data?.label ?? props.label ?? props.id)
const isActive = computed(() => !!props.data?.active)
const isTraversed = computed(() => !!props.data?.traversed)

const stateClass = computed(() => {
  if (isActive.value) return 'is-active'
  if (isTraversed.value) return 'is-traversed'
  return ''
})
</script>

<template>
  <div class="mcp-node" :class="stateClass" v-tooltip.bottom="fullLabel">
    <!-- MCP servers are leaves of the tool-call flow — only target handle.
         Two handles at the same position confuses VueFlow's auto-resolution,
         which silently dropped incoming tool edges. -->
    <Handle type="target" :position="Position.Top" />
    <span class="label">{{ fullLabel }}</span>
  </div>
</template>

<style scoped>
.mcp-node {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0.25rem 0.5rem;
  border-radius: 0.5rem;
  background-color: var(--p-surface-800);
  border: 1px solid var(--p-surface-700);
  color: #fff;
  font-size: 0.72rem;
  font-weight: 500;
  letter-spacing: 0.02em;
  line-height: 1.1;
  transition: background-color 200ms ease, border-color 200ms ease, box-shadow 200ms ease;
}
.mcp-node .label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}
/* Traversed = dimmed red on the dark MCP base so the contacted servers
 * stay highlighted after the answer is shown. New turn resets. */
.mcp-node.is-traversed {
  background-color: var(--p-primary-900);
  border-color: var(--p-primary-700);
  color: var(--p-primary-100);
}
.mcp-node.is-active {
  background-color: var(--p-primary-600);
  border-color: var(--p-primary-400);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--p-primary-500) 35%, transparent);
}
</style>
