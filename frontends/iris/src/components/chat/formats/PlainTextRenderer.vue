<script setup lang="ts">
// Stage 07-B — v2 plaintext renderer. The old `PlaintextDetails` had no
// fields and was removed from envelope.ts when the v2 shape landed.

defineProps<{
  text?: string
  content?: unknown
  details?: Record<string, never>
}>()
</script>

<template>
  <pre class="plaintext-body">{{ text ?? '' }}</pre>
</template>

<style scoped>
.plaintext-body {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 0.875rem;
  line-height: 1.5;
  margin: 0;
  color: inherit;
}
</style>
