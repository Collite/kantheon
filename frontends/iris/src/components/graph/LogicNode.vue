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
  <div class="logic-node" :class="stateClass" v-tooltip.top="fullLabel">
    <Handle type="target" :position="Position.Left" />
    <span class="label">{{ fullLabel }}</span>
    <Handle type="source" :position="Position.Right" />
    <Handle type="source" :position="Position.Bottom" id="mcp-out" style="opacity: 0" />
  </div>
</template>

<style scoped>
.logic-node {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0.25rem 0.5rem;
  border-radius: 0.5rem;
  background-color: #fff;
  border: 1px solid var(--p-surface-300);
  color: var(--p-surface-900);
  font-size: 0.78rem;
  font-weight: 500;
  line-height: 1.1;
  transition: background-color 200ms ease, border-color 200ms ease, box-shadow 200ms ease, color 200ms ease;
}
.logic-node .label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}
/* Traversed = dimmed red so the path stays visible after the answer is
 * shown (the user can inspect which logic steps led to the result).
 * A new turn clears `traversedNodes` and the node reverts to default. */
.logic-node.is-traversed {
  background-color: var(--p-primary-50);
  border-color: var(--p-primary-300);
  color: var(--p-primary-700);
}
.logic-node.is-active {
  background-color: var(--p-primary-500);
  border-color: var(--p-primary-600);
  color: #fff;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--p-primary-500) 35%, transparent);
}
</style>
