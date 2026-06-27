<script setup lang="ts">
// Defensive fallback for an unknown `format.kind` (FE-3.6, architecture.md
// §8). The agent cannot smuggle arbitrary HTML or unsupported rendering
// shapes — the catalog only renders kinds it knows about; everything else
// drops through here.
import Message from 'primevue/message'
import { useI18n } from 'vue-i18n'

defineProps<{
  text?: string
  details?: unknown
  kind?: string
}>()

const { t } = useI18n()
</script>

<template>
  <div class="unsupported">
    <Message severity="warn" :closable="false">
      {{ t('errors.unsupportedFormat', { kind: kind ?? 'unknown' }) }}
    </Message>
    <pre class="unsupported-body">{{ text ?? '' }}</pre>
  </div>
</template>

<style scoped>
.unsupported-body {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 0.875rem;
  margin: 0.5rem 0 0 0;
  color: inherit;
}
</style>
