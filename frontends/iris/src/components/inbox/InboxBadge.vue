<script setup lang="ts">
// Iris Phase 4 Stage 4.1 — investigation inbox header badge (PD-2). Shows the
// Running + Needs-input counts; live via the inbox store's SSE stream.
import { onMounted, onBeforeUnmount } from 'vue'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import { useInboxStore } from '@/stores/inboxStore'

const store = useInboxStore()
const { running, needsInput, hasActivity } = storeToRefs(store)
const { t } = useI18n()

onMounted(() => {
  void store.load()
  store.startStream()
})
onBeforeUnmount(() => store.stopStream())
</script>

<template>
  <div
    v-if="hasActivity"
    class="inbox-badge"
    :aria-label="t('inbox.badgeAria', { running, needsInput })"
    role="status"
  >
    <span class="pi pi-compass inbox-badge-icon" aria-hidden="true" />
    <span v-if="running > 0" class="inbox-badge-running" :title="t('inbox.running')">{{ running }}</span>
    <span v-if="needsInput > 0" class="inbox-badge-needs" :title="t('inbox.needsInput')">{{ needsInput }}</span>
  </div>
</template>

<style scoped>
.inbox-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.15rem 0.45rem;
  border-radius: 0.6rem;
  background: var(--p-surface-100);
  font-size: 0.72rem;
}
.inbox-badge-icon {
  font-size: 0.8rem;
  color: var(--p-surface-600);
}
.inbox-badge-running {
  color: var(--p-primary-700);
  font-weight: 600;
}
.inbox-badge-needs {
  color: var(--p-amber-700, #b45309);
  font-weight: 600;
}
</style>
